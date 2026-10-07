package com.positivity.accounting.tenancy;

import static com.positivity.tenancy.testing.TenantTestSupport.asTenant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.accounting.internal.bankrec.dto.BankReconciliationResponse;
import com.positivity.accounting.internal.bankrec.dto.BankStatementCreateRequest;
import com.positivity.accounting.internal.bankrec.dto.CloseReadinessResponse;
import com.positivity.accounting.internal.bankrec.dto.OutstandingItemRegisterRequest;
import com.positivity.accounting.internal.bankrec.dto.ReconciliationCreateRequest;
import com.positivity.accounting.internal.bankrec.dto.ReconciliationMatchCreateRequest;
import com.positivity.accounting.internal.bankrec.entity.BankReconciliationOutstandingItem;
import com.positivity.accounting.internal.bankrec.entity.BankTransaction;
import com.positivity.accounting.internal.bankrec.enums.OutstandingItemKind;
import com.positivity.accounting.internal.bankrec.enums.OutstandingItemStatus;
import com.positivity.accounting.internal.bankrec.enums.ReadinessCheckCode;
import com.positivity.accounting.internal.bankrec.enums.ReadinessSeverity;
import com.positivity.accounting.internal.bankrec.intake.BankRecErrorCode;
import com.positivity.accounting.internal.bankrec.intake.BankRecException;
import com.positivity.accounting.internal.bankrec.readmodel.BankReconciliationCloseReadiness;
import com.positivity.accounting.internal.bankrec.repository.BankReconciliationOutstandingItemRepository;
import com.positivity.accounting.internal.bankrec.repository.BankTransactionRepository;
import com.positivity.accounting.internal.bankrec.service.BankReconciliationService;
import com.positivity.accounting.internal.bankrec.service.BankStatementService;
import com.positivity.accounting.internal.bankrec.service.ReconciliationMatchingService;
import com.positivity.accounting.internal.bankrec.service.ReconciliationOutstandingItemService;
import com.positivity.accounting.internal.dto.BankOpeningBalanceRequest;
import com.positivity.accounting.internal.dto.BankOpeningBalanceResponse;
import com.positivity.accounting.internal.dto.JournalEntryCreateRequest;
import com.positivity.accounting.internal.entity.AccountingPeriod;
import com.positivity.accounting.internal.entity.GLAccount;
import com.positivity.accounting.internal.enums.AccountSubtype;
import com.positivity.accounting.internal.enums.AccountType;
import com.positivity.accounting.internal.enums.AccountingPeriodStatus;
import com.positivity.accounting.internal.enums.BankOpeningItemType;
import com.positivity.accounting.internal.exception.AccountingPeriodClosedException;
import com.positivity.accounting.internal.exception.AccountingPeriodHardLockedException;
import com.positivity.accounting.internal.exception.CashSetupException;
import com.positivity.accounting.internal.exception.CurrencyNotSupportedException;
import com.positivity.accounting.internal.exception.GLAccountNotFoundException;
import com.positivity.accounting.internal.exception.InvalidRequestParameterException;
import com.positivity.accounting.internal.repository.BankOpeningBalanceRepository;
import com.positivity.accounting.internal.repository.GLAccountRepository;
import com.positivity.accounting.internal.repository.JournalEntryLineRepository;
import com.positivity.accounting.internal.service.BankOpeningBalanceService;
import com.positivity.accounting.internal.service.GLMappingResolver;
import com.positivity.accounting.internal.service.JournalEntryService;
import com.positivity.security.common.GatewaySecurityConstants;
import com.positivity.shared.id.UUIDv7Generator;
import com.positivity.tenancy.TenantContext;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The bank opening balance on the full Flyway chain (#2572, OI-10): the template's OPENING_BALANCE mapping to 3900,
 * the worked example's entry and book balance, the first statement opening with no difference once the items are
 * registered as outstanding (SPEC-manual-bank-reconciliation E2, D17, §4.2), once per account with reverse and
 * re-run, the opening coming first, idempotency, the period gate and two-tenant isolation (ADR-0062).
 *
 * <p>Requires Docker.
 */
@DisplayName("Bank opening balance through 3900 (#2572, real Postgres)")
class BankOpeningBalancePostgresIT extends PostgresTenancyTestBase {

    private static final LocalDate CUTOVER = LocalDate.of(2025, 10, 31);
    private static final String WHY = "Opening balance per the October bank statement";
    private static final String ACK = "First statement on the platform, after the opening balance";
    private static final String CHECK = "1043";
    private static final String DEPOSIT = "DEP-1031";

    @Autowired
    private BankOpeningBalanceService openings;

    @Autowired
    private BankOpeningBalanceRepository openingRows;

    @Autowired
    private GLAccountRepository glAccounts;

    @Autowired
    private GLMappingResolver resolver;

    @Autowired
    private JournalEntryService journalEntries;

    @Autowired
    private JournalEntryLineRepository ledgerLines;

    @Autowired
    private BankStatementService statementService;

    @Autowired
    private BankTransactionRepository bankTransactions;

    @Autowired
    private BankReconciliationService reconciliationService;

    @Autowired
    private ReconciliationOutstandingItemService itemService;

    @Autowired
    private ReconciliationMatchingService matching;

    @Autowired
    private BankReconciliationOutstandingItemRepository itemRows;

    @Autowired
    private BankReconciliationCloseReadiness readiness;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private java.time.Clock clock;

    private final List<UUID> tenants = new ArrayList<>();

    @AfterEach
    void removeTestTenants() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
        JdbcTemplate owner = new JdbcTemplate(ownerDataSource());
        List<String> scoped = owner.queryForList(
                "SELECT table_name FROM information_schema.columns WHERE table_schema = 'public'"
                        + " AND column_name = 'tenant_id' AND table_name NOT LIKE 'pg_%' ORDER BY table_name",
                String.class);
        for (UUID tenant : tenants) {
            owner.update(
                    "UPDATE journal_entry SET reversal_journal_entry_id = NULL, reversed_by_journal_entry_id = NULL"
                            + " WHERE tenant_id = ?",
                    tenant);
            owner.update("UPDATE bank_reconciliation_match SET replaces_match_id = NULL WHERE tenant_id = ?", tenant);
            for (int pass = 0; pass < 8; pass++) {
                boolean blocked = false;
                for (String table : scoped) {
                    try {
                        owner.update("DELETE FROM " + table + " WHERE tenant_id = ?", tenant);
                    } catch (RuntimeException stillReferenced) {
                        blocked = true;
                    }
                }
                if (!blocked) {
                    break;
                }
            }
        }
        tenants.clear();
    }

    @Test
    @DisplayName("AC1/AC6/AC7: the worked example posts Dr 1000 10,000 / Cr 1000 450 / Dr 1000 1,200 / Cr 3900 10,750,"
            + " and the first statement (11-01..11-30) opens with a difference of 0.00 once the items are registered")
    void workedExampleOpensTheFirstStatementWithNoDifference() {
        UUID tenant = tenant();
        signIn(
                "controller.cfo",
                "accounting:je:create",
                "accounting:je:post",
                "accounting:reconciliation:adjust",
                "accounting:reconciliation:approve");
        UUID cash = accountId(tenant, "1000");
        assertThat(code(
                        tenant,
                        asTenant(
                                tenant,
                                () -> resolver.resolveGLAccount(
                                        "OPENING_BALANCE", "OPENING_BALANCE_EQUITY", CUTOVER.atStartOfDay()))))
                .as("the template maps OPENING_BALANCE / OPENING_BALANCE_EQUITY to 3900")
                .isEqualTo("3900");

        BankOpeningBalanceResponse opened = asTenant(
                        tenant, () -> openings.establish(cash, workedExample(UUIDv7Generator.generate())))
                .response();

        // AC1/AC7: the entry, dated the cutover, each item line carrying its reference and date.
        assertThat(lines(tenant, opened.journalEntryId()))
                .containsExactly("1000 D10000.0000", "1000 C450.0000", "1000 D1200.0000", "3900 C10750.0000");
        assertThat(new JdbcTemplate(ownerDataSource())
                        .queryForObject(
                                "SELECT transaction_date::date FROM journal_entry WHERE tenant_id = ? AND"
                                        + " journal_entry_id = ?",
                                LocalDate.class,
                                tenant,
                                opened.journalEntryId()))
                .isEqualTo(CUTOVER);
        assertThat(opened.bookBalance()).isEqualByComparingTo("10750.00");
        assertThat(opened.currencyCode()).isEqualTo("USD");
        assertThat(asTenant(tenant, () -> ledgerLines.getAccountBalanceAsOf(cash, CUTOVER.atTime(23, 59, 59))))
                .as("book balance = statement + deposits in transit - outstanding checks")
                .isEqualByComparingTo("10750.00");
        assertThat(opened.outstandingItems())
                .extracting(BankOpeningBalanceResponse.Item::reference)
                .containsExactly(CHECK, DEPOSIT);
        UUID checkLine = opened.outstandingItems().get(0).glLineId();
        UUID depositLine = opened.outstandingItems().get(1).glLineId();

        // AC6: the first statement starts on the day after the cutover, opens at the statement balance and carries
        // the first-statement gap acknowledgement.
        UUID statementId = inTx(
                tenant,
                () -> statementService
                        .createManualStatement(BankStatementCreateRequest.builder()
                                .glAccountId(cash)
                                .requestId(UUIDv7Generator.generate())
                                .statement(BankStatementCreateRequest.Header.builder()
                                        .startDate(LocalDate.of(2025, 11, 1))
                                        .endDate(LocalDate.of(2025, 11, 30))
                                        .openingBalance(new BigDecimal("10000.00"))
                                        .closingBalance(new BigDecimal("10750.00"))
                                        .build())
                                .transactions(List.of(
                                        bankRow(LocalDate.of(2025, 11, 3), "1200.00", "DEPOSIT " + DEPOSIT),
                                        bankRow(LocalDate.of(2025, 11, 5), "-450.00", "CHECK " + CHECK)))
                                .gapAcknowledgement(ACK)
                                .build())
                        .getStatementId());
        UUID reconId = inTx(
                tenant,
                () -> reconciliationService
                        .create(ReconciliationCreateRequest.builder()
                                .glAccountId(cash)
                                .requestId(UUIDv7Generator.generate())
                                .statementId(statementId)
                                .build())
                        .getReconciliationId());
        assertThat(reconciliation(tenant, reconId).getOpeningDifference())
                .as("before the items are registered the opening differs by their net")
                .isEqualByComparingTo("-750.00");

        // The preparer registers the itemised check and deposit as outstanding items.
        inTx(tenant, () -> itemService.register(reconId, register(checkLine, OutstandingItemKind.OUTSTANDING_CHECK)));
        inTx(
                tenant,
                () -> itemService.register(reconId, register(depositLine, OutstandingItemKind.DEPOSIT_IN_TRANSIT)));
        assertThat(reconciliation(tenant, reconId).getOpeningDifference())
                .as("AC7: with every item registered the first statement opens with no difference")
                .isEqualByComparingTo("0.00");
        BankReconciliationOutstandingItem check = asTenant(
                tenant,
                () -> itemRows.findByGlLineIdInAndStatus(List.of(checkLine), OutstandingItemStatus.OPEN)
                        .get(0));
        assertThat(check.getItemDate())
                .as("the item keeps its own date, not the cutover date the entry is dated on")
                .isEqualTo(LocalDate.of(2025, 10, 28));
        assertThat(check.getSignedAmount()).isEqualByComparingTo("-450.00");

        // They match 1:1 when they clear.
        List<UUID> rows = inTx(
                tenant,
                () -> bankTransactions.findByStatementIdOrderBySourceRowNumberAsc(statementId).stream()
                        .map(BankTransaction::getBankTransactionId)
                        .toList());
        inTx(tenant, () -> matching.createMatch(reconId, match(rows.get(0), depositLine)));
        inTx(tenant, () -> matching.createMatch(reconId, match(rows.get(1), checkLine)));
        BankReconciliationResponse cleared = reconciliation(tenant, reconId);
        assertThat(cleared.getOpeningDifference()).isEqualByComparingTo("0.00");
        assertThat(cleared.getDifference()).isEqualByComparingTo("0.00");
        assertThat(cleared.getCountUnexplainedBank()).isZero();
        assertThat(cleared.getCountUnexplainedLedger()).isZero();
        assertThat(asTenant(
                        tenant,
                        () -> itemRows.findByGlLineIdInAndStatus(
                                List.of(checkLine, depositLine), OutstandingItemStatus.CLEARED)))
                .hasSize(2);

        // AC9: 3900 is not cleared at the period end: S15's readiness warning covers it, never a block.
        CloseReadinessResponse warned =
                asTenant(tenant, () -> readiness.evaluate(period("2025-10", LocalDate.of(2025, 10, 1), CUTOVER)));
        assertThat(warned.checks())
                .filteredOn(c -> c.code() == ReadinessCheckCode.OPENING_BALANCE_EQUITY_NOT_CLEARED)
                .singleElement()
                .satisfies(c -> assertThat(c.severity()).isEqualTo(ReadinessSeverity.WARNING));
    }

    @Test
    @DisplayName("AC4/AC9: once per account, replay returns the first result, another body is 409, and a reversed"
            + " opening is re-run; another tenant sees none of it")
    void oncePerAccountReverseAndRerun() {
        UUID tenant = tenant();
        signIn("controller.cfo", "accounting:je:create", "accounting:je:post");
        UUID cash = accountId(tenant, "1000");
        UUID requestId = UUIDv7Generator.generate();

        BankOpeningBalanceService.Outcome first =
                asTenant(tenant, () -> openings.establish(cash, request("500.00", List.of(), requestId)));
        assertThat(first.replayed()).isFalse();

        // AC9: the same requestId and body replays the first result; another body is 409.
        int entries = count(tenant, "journal_entry");
        BankOpeningBalanceService.Outcome replay =
                asTenant(tenant, () -> openings.establish(cash, request("500.00", List.of(), requestId)));
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.response().journalEntryId())
                .isEqualTo(first.response().journalEntryId());
        assertThat(count(tenant, "journal_entry")).isEqualTo(entries);
        assertRefused(
                tenant, cash, request("501.00", List.of(), requestId), CashSetupException.Code.IDEMPOTENCY_CONFLICT);

        // AC4: a standing opening refuses another.
        assertRefused(
                tenant,
                cash,
                request("600.00", List.of(), UUIDv7Generator.generate()),
                CashSetupException.Code.BANK_OPENING_BALANCE_ALREADY_ESTABLISHED);
        assertThat(count(tenant, "journal_entry")).isEqualTo(entries);

        // AC4: correction = reverse and re-run. The reversal is dated the cutover too: a reversal pair never stands.
        asTenant(
                tenant,
                () -> journalEntries.reverseJournalEntry(
                        first.response().journalEntryId(), "Wrong statement balance keyed", CUTOVER));
        BankOpeningBalanceResponse again = asTenant(
                        tenant,
                        () -> openings.establish(cash, request("600.00", List.of(), UUIDv7Generator.generate())))
                .response();
        assertThat(again.bookBalance()).isEqualByComparingTo("600.00");
        assertThat(asTenant(tenant, () -> openingRows.findStandingByGlAccountId(cash)))
                .singleElement()
                .satisfies(row -> assertThat(row.getJournalEntryId()).isEqualTo(again.journalEntryId()));
        assertThat(asTenant(tenant, () -> ledgerLines.getAccountBalanceAsOf(cash, CUTOVER.atTime(23, 59, 59))))
                .isEqualByComparingTo("600.00");

        // Two-tenant isolation (ADR-0062): another tenant sees no opening and cannot replay this one.
        UUID other = tenant();
        assertThat(asTenant(other, () -> openingRows.findByRequestId(requestId)))
                .isEmpty();
        assertThat(asTenant(other, () -> openingRows.findAll())).isEmpty();
    }

    @Test
    @DisplayName("AC3/AC5/ADR-0067: a missing or non-bank account, another currency, sub-cent amounts, a line in the"
            + " cutover's balance (a later-reversed one too), an earlier statement or an empty opening is refused; a"
            + " line after the cutover, or a pair reversed before it, is not")
    void openingMustComeFirst() {
        UUID tenant = tenant();
        signIn("controller.cfo", "accounting:je:create", "accounting:je:post", "accounting:reconciliation:adjust");
        UUID equity = accountId(tenant, "3900");
        UUID revenue = accountId(tenant, "4000");

        // ADR-0017: a path naming no account the caller can see is 404.
        UUID nowhere = UUIDv7Generator.generate();
        assertThatThrownBy(() -> asTenant(
                        tenant,
                        () -> openings.establish(nowhere, request("100.00", List.of(), UUIDv7Generator.generate()))))
                .isInstanceOf(GLAccountNotFoundException.class);

        // AC3: not a bank account.
        assertRefused(
                tenant,
                equity,
                request("100.00", List.of(), UUIDv7Generator.generate()),
                CashSetupException.Code.BANK_OPENING_BALANCE_ACCOUNT_NOT_ELIGIBLE);

        // AC5: a standing posted line dated on or before the cutover.
        UUID traded = bankAccount(tenant);
        post(tenant, traded, revenue, "80.00", LocalDate.of(2025, 10, 15));
        assertRefused(
                tenant,
                traded,
                request("100.00", List.of(), UUIDv7Generator.generate()),
                CashSetupException.Code.BANK_OPENING_BALANCE_NOT_FIRST);

        // MINOR-3: a line before the cutover reversed after it is still in the cutover's balance (as bank
        // reconciliation's getAccountBalanceAsOf counts it): not first. A pair dated wholly before it nets out.
        UUID reversedLater = bankAccount(tenant);
        UUID early = post(tenant, reversedLater, revenue, "80.00", LocalDate.of(2025, 10, 15));
        asTenant(tenant, () -> journalEntries.reverseJournalEntry(early, "Keyed twice", CUTOVER.plusDays(5)));
        assertRefused(
                tenant,
                reversedLater,
                request("100.00", List.of(), UUIDv7Generator.generate()),
                CashSetupException.Code.BANK_OPENING_BALANCE_NOT_FIRST);
        UUID reversedBefore = bankAccount(tenant);
        UUID pair = post(tenant, reversedBefore, revenue, "80.00", LocalDate.of(2025, 10, 15));
        asTenant(tenant, () -> journalEntries.reverseJournalEntry(pair, "Keyed twice", LocalDate.of(2025, 10, 20)));
        assertThat(asTenant(
                                tenant,
                                () -> openings.establish(
                                        reversedBefore, request("100.00", List.of(), UUIDv7Generator.generate())))
                        .replayed())
                .isFalse();

        // AC5: a committed statement starting on or before the cutover.
        UUID stated = bankAccount(tenant);
        inTx(
                tenant,
                () -> statementService.createManualStatement(BankStatementCreateRequest.builder()
                        .glAccountId(stated)
                        .requestId(UUIDv7Generator.generate())
                        .statement(BankStatementCreateRequest.Header.builder()
                                .startDate(LocalDate.of(2025, 10, 1))
                                .endDate(CUTOVER)
                                .openingBalance(BigDecimal.ZERO)
                                .closingBalance(new BigDecimal("50.00"))
                                .build())
                        .transactions(List.of(bankRow(LocalDate.of(2025, 10, 10), "50.00", "TRANSFER IN")))
                        .gapAcknowledgement(ACK)
                        .build()));
        assertRefused(
                tenant,
                stated,
                request("100.00", List.of(), UUIDv7Generator.generate()),
                CashSetupException.Code.BANK_OPENING_BALANCE_NOT_FIRST);

        // ADR-0067 PC-9: the amounts must be in the account's currency, else 422 CURRENCY_NOT_SUPPORTED.
        UUID fresh = bankAccount(tenant);
        BankOpeningBalanceRequest euros = new BankOpeningBalanceRequest(
                CUTOVER, new BigDecimal("100.00"), "EUR", List.of(), WHY, UUIDv7Generator.generate());
        assertThatThrownBy(() -> asTenant(tenant, () -> openings.establish(fresh, euros)))
                .isInstanceOf(CurrencyNotSupportedException.class);

        // ADR-0067 PC-6: amounts finer than the minor unit, every one named, 422.
        BankOpeningBalanceRequest fine = request(
                "100.001",
                List.of(new BankOpeningBalanceRequest.OutstandingItem(
                        BankOpeningItemType.OUTSTANDING_CHECK, "7", CUTOVER, new BigDecimal("1.005"))),
                UUIDv7Generator.generate());
        assertThatThrownBy(() -> asTenant(tenant, () -> openings.establish(fresh, fine)))
                .isInstanceOfSatisfying(BankRecException.class, e -> {
                    assertThat(e.code()).isEqualTo(BankRecErrorCode.AMOUNT_PRECISION_EXCEEDS_CURRENCY);
                    assertThat(e.fieldErrors()).containsOnlyKeys("statementBalance", "outstandingItems[0].amount");
                });

        // AC2: the cutover is not after today in the tenant's calendar (UTC here): 400 naming asOfDate.
        LocalDate tomorrow =
                LocalDate.ofInstant(clock.instant(), java.time.ZoneOffset.UTC).plusDays(1);
        BankOpeningBalanceRequest future = new BankOpeningBalanceRequest(
                tomorrow, new BigDecimal("100.00"), "USD", List.of(), WHY, UUIDv7Generator.generate());
        assertThatThrownBy(() -> asTenant(tenant, () -> openings.establish(fresh, future)))
                .isInstanceOfSatisfying(
                        InvalidRequestParameterException.class,
                        e -> assertThat(e.getField()).isEqualTo("asOfDate"));

        // AC5: a zero balance with no items.
        assertRefused(
                tenant,
                fresh,
                request("0.00", List.of(), UUIDv7Generator.generate()),
                CashSetupException.Code.BANK_OPENING_BALANCE_EMPTY);

        // AC5: lines dated after the cutover are allowed, so the opening can follow the start of trading.
        post(tenant, fresh, revenue, "80.00", CUTOVER.plusDays(3));
        assertThat(asTenant(
                                tenant,
                                () -> openings.establish(
                                        fresh, request("100.00", List.of(), UUIDv7Generator.generate())))
                        .response()
                        .bookBalance())
                .isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("MAJOR-1: only an opening entry's own item lines date their items; forged dimensions on a manual"
            + " entry and the dimensions a reversal copies keep the entry's date")
    void onlyAnOpeningEntryDatesItsItems() {
        UUID tenant = tenant();
        signIn(
                "controller.cfo",
                "accounting:je:create",
                "accounting:je:post",
                "accounting:je:reverse",
                "accounting:reconciliation:adjust");
        UUID cash = accountId(tenant, "1000");
        UUID revenue = accountId(tenant, "4000");
        BankOpeningBalanceResponse opened = asTenant(
                        tenant, () -> openings.establish(cash, workedExample(UUIDv7Generator.generate())))
                .response();

        // A manual entry whose cash line forges the opening's dimensions with an old date.
        UUID forgedEntry = post(
                tenant,
                cash,
                revenue,
                "100.00",
                LocalDate.of(2025, 11, 10),
                Map.of("outstandingItemType", "DEPOSIT_IN_TRANSIT", "reference", "X-1", "itemDate", "2025-06-01"));
        UUID forgedLine = lineOf(tenant, forgedEntry, cash);
        // The opening reversed on 11-05: its reversal copies the check line's dimensions (itemDate 2025-10-28).
        UUID reversalEntry = asTenant(
                tenant,
                () -> journalEntries
                        .reverseJournalEntry(
                                opened.journalEntryId(), "Statement balance keyed wrong", CUTOVER.plusDays(5))
                        .getJournalEntryId());
        UUID reversedCheckLine = new JdbcTemplate(ownerDataSource())
                .queryForObject(
                        "SELECT line_id FROM journal_entry_line WHERE tenant_id = ? AND journal_entry_id = ? AND"
                                + " dimensions ->> 'reference' = ?",
                        UUID.class,
                        tenant,
                        reversalEntry,
                        CHECK);

        UUID statementId = inTx(
                tenant,
                () -> statementService
                        .createManualStatement(BankStatementCreateRequest.builder()
                                .glAccountId(cash)
                                .requestId(UUIDv7Generator.generate())
                                .statement(BankStatementCreateRequest.Header.builder()
                                        .startDate(LocalDate.of(2025, 11, 1))
                                        .endDate(LocalDate.of(2025, 11, 30))
                                        .openingBalance(new BigDecimal("10000.00"))
                                        .closingBalance(new BigDecimal("10010.00"))
                                        .build())
                                .transactions(List.of(bankRow(LocalDate.of(2025, 11, 20), "10.00", "INTEREST")))
                                .gapAcknowledgement(ACK)
                                .build())
                        .getStatementId());
        UUID reconId = inTx(
                tenant,
                () -> reconciliationService
                        .create(ReconciliationCreateRequest.builder()
                                .glAccountId(cash)
                                .requestId(UUIDv7Generator.generate())
                                .statementId(statementId)
                                .build())
                        .getReconciliationId());

        LocalDate forgedDate = inTx(
                        tenant,
                        () -> itemService.register(
                                reconId, register(forgedLine, OutstandingItemKind.DEPOSIT_IN_TRANSIT)))
                .getItemDate();
        assertThat(forgedDate).as("a manual entry's forged itemDate").isEqualTo(LocalDate.of(2025, 11, 10));
        LocalDate reversalDate = inTx(
                        tenant,
                        () -> itemService.register(
                                reconId, register(reversedCheckLine, OutstandingItemKind.DEPOSIT_IN_TRANSIT)))
                .getItemDate();
        assertThat(reversalDate).as("a reversal's copied itemDate").isEqualTo(CUTOVER.plusDays(5));
    }

    @Test
    @DisplayName("AC2: a cutover in a CLOSED period is 422 PERIOD_CLOSED even with accounting:period:override, and"
            + " one before the hard lock is 422 PERIOD_HARD_LOCKED")
    void cutoverNeedsAnOpenPeriod() {
        UUID tenant = tenant();
        JdbcTemplate owner = new JdbcTemplate(ownerDataSource());
        owner.update(
                "INSERT INTO accounting_period (tenant_id, period_id, period_code, start_date, end_date, status,"
                        + " created_at, created_by, modified_at, modified_by, version) VALUES (?, ?, '2025-08',"
                        + " DATE '2025-08-01', DATE '2025-08-31', 'CLOSED', TIMESTAMPTZ '2025-09-01 00:00:00+00',"
                        + " 't', TIMESTAMPTZ '2025-09-01 00:00:00+00', 't', 0)",
                tenant,
                UUIDv7Generator.generate());
        owner.update(
                "INSERT INTO accounting_configuration (tenant_id, config_id, config_key, config_value, created_at,"
                        + " created_by, modified_at, modified_by) VALUES (?, ?, 'HARD_LOCK_DATE', '2025-06-01',"
                        + " TIMESTAMPTZ '2025-09-01 00:00:00+00', 't', TIMESTAMPTZ '2025-09-01 00:00:00+00', 't')",
                tenant,
                UUIDv7Generator.generate());
        signIn("controller.cfo", "accounting:je:create", "accounting:je:post", "accounting:period:override");
        UUID cash = accountId(tenant, "1000");

        BankOpeningBalanceRequest closed = new BankOpeningBalanceRequest(
                LocalDate.of(2025, 8, 31), new BigDecimal("100.00"), "USD", List.of(), WHY, UUIDv7Generator.generate());
        assertThatThrownBy(() -> asTenant(tenant, () -> openings.establish(cash, closed)))
                .isInstanceOf(AccountingPeriodClosedException.class);
        BankOpeningBalanceRequest locked = new BankOpeningBalanceRequest(
                LocalDate.of(2025, 5, 31), new BigDecimal("100.00"), "USD", List.of(), WHY, UUIDv7Generator.generate());
        assertThatThrownBy(() -> asTenant(tenant, () -> openings.establish(cash, locked)))
                .isInstanceOf(AccountingPeriodHardLockedException.class);
        assertThat(count(tenant, "bank_opening_balance")).isZero();
        assertThat(count(tenant, "journal_entry")).isZero();
    }

    // ---- helpers --------------------------------------------------------------------------------------------

    private UUID tenant() {
        UUID tenant = tenantWithZone();
        tenants.add(tenant);
        provisionAccounting(tenant);
        return tenant;
    }

    private UUID accountId(UUID tenant, String code) {
        return asTenant(
                tenant, () -> glAccounts.findByAccountCode(code).orElseThrow().getGlAccountId());
    }

    private String code(UUID tenant, UUID accountId) {
        return asTenant(
                tenant, () -> glAccounts.findById(accountId).orElseThrow().getAccountCode());
    }

    /** A reconcilable bank account of the tenant's own, beside the template's 1000. */
    private UUID bankAccount(UUID tenant) {
        String suffix = UUIDv7Generator.generate().toString().substring(28);
        return asTenant(tenant, () -> {
            GLAccount account = new GLAccount();
            account.setGlAccountId(UUIDv7Generator.generate());
            account.setAccountCode("B" + suffix);
            account.setAccountName("Opening IT bank " + suffix);
            account.setAccountType(AccountType.ASSET);
            account.setAccountSubtype(AccountSubtype.BANK_CASH);
            account.setReconcilable(true);
            account.setActivationDate(LocalDateTime.of(2020, 1, 1, 0, 0));
            account.setCreatedBy("it");
            account.setModifiedBy("it");
            return glAccounts.save(account).getGlAccountId();
        });
    }

    /** Posts Dr cash / Cr counter of {@code amount} on {@code day}; returns the entry id. */
    private UUID post(UUID tenant, UUID cash, UUID counter, String amount, LocalDate day) {
        return post(tenant, cash, counter, amount, day, null);
    }

    /** As {@link #post(UUID, UUID, UUID, String, LocalDate)}, the cash line carrying {@code dimensions}. */
    private UUID post(
            UUID tenant, UUID cash, UUID counter, String amount, LocalDate day, Map<String, String> dimensions) {
        BigDecimal value = new BigDecimal(amount);
        return asTenant(tenant, () -> {
            UUID created = journalEntries
                    .createJournalEntry(JournalEntryCreateRequest.builder()
                            .sourceEventType("TEST")
                            .transactionDate(day.atTime(12, 0))
                            .sourceEventId(UUIDv7Generator.generate())
                            .description("Opening IT receipt")
                            .lines(List.of(
                                    JournalEntryCreateRequest.JournalEntryLineRequest.builder()
                                            .glAccountId(cash)
                                            .debitAmount(value)
                                            .creditAmount(BigDecimal.ZERO)
                                            .dimensions(dimensions)
                                            .build(),
                                    JournalEntryCreateRequest.JournalEntryLineRequest.builder()
                                            .glAccountId(counter)
                                            .debitAmount(BigDecimal.ZERO)
                                            .creditAmount(value)
                                            .build()))
                            .build())
                    .getJournalEntryId();
            return journalEntries.postJournalEntry(created, null).getJournalEntryId();
        });
    }

    /** The line of {@code entryId} on {@code account}. */
    private static UUID lineOf(UUID tenant, UUID entryId, UUID account) {
        return new JdbcTemplate(ownerDataSource())
                .queryForObject(
                        "SELECT line_id FROM journal_entry_line WHERE tenant_id = ? AND journal_entry_id = ? AND"
                                + " gl_account_id = ?",
                        UUID.class,
                        tenant,
                        entryId,
                        account);
    }

    private void assertRefused(
            UUID tenant, UUID account, BankOpeningBalanceRequest request, CashSetupException.Code code) {
        int entries = count(tenant, "journal_entry");
        assertThatThrownBy(() -> asTenant(tenant, () -> openings.establish(account, request)))
                .isInstanceOfSatisfying(
                        CashSetupException.class, e -> assertThat(e.getCode()).isEqualTo(code));
        assertThat(count(tenant, "journal_entry")).as("a refusal posts nothing").isEqualTo(entries);
    }

    private BankReconciliationResponse reconciliation(UUID tenant, UUID reconId) {
        return inTx(tenant, () -> reconciliationService.get(reconId));
    }

    private <T> T inTx(UUID tenant, Callable<T> work) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        return asTenant(
                tenant,
                () -> tx.execute(status -> {
                    try {
                        return work.call();
                    } catch (RuntimeException e) {
                        throw e;
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }));
    }

    private static BankOpeningBalanceRequest workedExample(UUID requestId) {
        return request(
                "10000.00",
                List.of(
                        new BankOpeningBalanceRequest.OutstandingItem(
                                BankOpeningItemType.OUTSTANDING_CHECK,
                                CHECK,
                                LocalDate.of(2025, 10, 28),
                                new BigDecimal("450.00")),
                        new BankOpeningBalanceRequest.OutstandingItem(
                                BankOpeningItemType.DEPOSIT_IN_TRANSIT, DEPOSIT, CUTOVER, new BigDecimal("1200.00"))),
                requestId);
    }

    private static BankOpeningBalanceRequest request(
            String statement, List<BankOpeningBalanceRequest.OutstandingItem> items, UUID requestId) {
        return new BankOpeningBalanceRequest(CUTOVER, new BigDecimal(statement), "USD", items, WHY, requestId);
    }

    private static BankStatementCreateRequest.Transaction bankRow(LocalDate date, String amount, String description) {
        return BankStatementCreateRequest.Transaction.builder()
                .date(date)
                .signedAmount(new BigDecimal(amount))
                .description(description)
                .build();
    }

    private static OutstandingItemRegisterRequest register(UUID glLineId, OutstandingItemKind kind) {
        // The dates here are a year old, past the aging days, so registration needs a justification (§3.6).
        return OutstandingItemRegisterRequest.builder()
                .glLineId(glLineId)
                .itemKind(kind)
                .justification("Outstanding at cutover, booked in the opening balance")
                .build();
    }

    private static ReconciliationMatchCreateRequest match(UUID bankTransactionId, UUID glLineId) {
        return ReconciliationMatchCreateRequest.builder()
                .bankTransactionIds(List.of(bankTransactionId))
                .glLineIds(List.of(glLineId))
                .justification("Cleared in November; booked in the opening balance")
                .requestId(UUIDv7Generator.generate())
                .build();
    }

    private static AccountingPeriod period(String code, LocalDate start, LocalDate end) {
        AccountingPeriod period = new AccountingPeriod();
        period.setPeriodId(UUIDv7Generator.generate());
        period.setPeriodCode(code);
        period.setStartDate(start);
        period.setEndDate(end);
        period.setStatus(AccountingPeriodStatus.OPEN);
        return period;
    }

    /** "code D|C amount" for each line of the entry, in line order. */
    private static List<String> lines(UUID tenant, UUID entryId) {
        return new JdbcTemplate(ownerDataSource())
                .query(
                        "SELECT g.account_code, l.debit_amount, l.credit_amount FROM journal_entry_line l JOIN"
                                + " gl_account g ON g.tenant_id = l.tenant_id AND g.gl_account_id = l.gl_account_id"
                                + " WHERE l.tenant_id = ? AND l.journal_entry_id = ? ORDER BY l.line_number",
                        (rs, n) -> {
                            BigDecimal debit = rs.getBigDecimal(2);
                            return rs.getString(1) + " "
                                    + (debit != null && debit.signum() > 0
                                            ? "D" + debit.toPlainString()
                                            : "C" + rs.getBigDecimal(3).toPlainString());
                        },
                        tenant,
                        entryId);
    }

    private static int count(UUID tenant, String table) {
        return new JdbcTemplate(ownerDataSource())
                .queryForObject("SELECT count(*) FROM " + table + " WHERE tenant_id = ?", Integer.class, tenant);
    }

    private static void signIn(String username, String... authorities) {
        UsernamePasswordAuthenticationToken caller = new UsernamePasswordAuthenticationToken(
                username,
                "n/a",
                java.util.Arrays.stream(authorities)
                        .map(SimpleGrantedAuthority::new)
                        .toList());
        caller.setDetails(Map.of(GatewaySecurityConstants.DETAIL_USERNAME, username));
        SecurityContextHolder.getContext().setAuthentication(caller);
    }
}
