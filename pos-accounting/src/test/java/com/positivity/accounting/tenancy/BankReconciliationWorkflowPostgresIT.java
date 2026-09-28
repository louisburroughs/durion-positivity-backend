package com.positivity.accounting.tenancy;

import static com.positivity.tenancy.testing.TenantTestSupport.TENANT_A;
import static com.positivity.tenancy.testing.TenantTestSupport.asTenant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.accounting.internal.bankrec.dto.AdjustmentReverseRequest;
import com.positivity.accounting.internal.bankrec.dto.BankReconciliationAdjustmentResponse;
import com.positivity.accounting.internal.bankrec.dto.BankReconciliationResponse;
import com.positivity.accounting.internal.bankrec.dto.BankStatementCreateRequest;
import com.positivity.accounting.internal.bankrec.dto.OutstandingItemRegisterRequest;
import com.positivity.accounting.internal.bankrec.dto.ReconciliationAdjustmentRequest;
import com.positivity.accounting.internal.bankrec.dto.ReconciliationCreateRequest;
import com.positivity.accounting.internal.bankrec.dto.ReconciliationMatchCreateRequest;
import com.positivity.accounting.internal.bankrec.dto.ReconciliationMatchResponse;
import com.positivity.accounting.internal.bankrec.dto.ReconciliationReviewResponse;
import com.positivity.accounting.internal.bankrec.entity.BankTransaction;
import com.positivity.accounting.internal.bankrec.enums.BankAdjustmentType;
import com.positivity.accounting.internal.bankrec.enums.BankTransactionStatus;
import com.positivity.accounting.internal.bankrec.enums.MatchKind;
import com.positivity.accounting.internal.bankrec.enums.MatchState;
import com.positivity.accounting.internal.bankrec.enums.OutstandingItemKind;
import com.positivity.accounting.internal.bankrec.enums.OutstandingItemStatus;
import com.positivity.accounting.internal.bankrec.intake.BankRecErrorCode;
import com.positivity.accounting.internal.bankrec.intake.BankRecException;
import com.positivity.accounting.internal.bankrec.repository.BankReconciliationMatchRepository;
import com.positivity.accounting.internal.bankrec.repository.BankReconciliationOutstandingItemRepository;
import com.positivity.accounting.internal.bankrec.repository.BankTransactionRepository;
import com.positivity.accounting.internal.bankrec.service.BankReconciliationService;
import com.positivity.accounting.internal.bankrec.service.BankStatementService;
import com.positivity.accounting.internal.bankrec.service.ReconciliationAdjustmentService;
import com.positivity.accounting.internal.bankrec.service.ReconciliationAdjustmentServiceImpl;
import com.positivity.accounting.internal.bankrec.service.ReconciliationMatchingService;
import com.positivity.accounting.internal.bankrec.service.ReconciliationOutstandingItemService;
import com.positivity.accounting.internal.bankrec.service.ReconciliationReviewService;
import com.positivity.accounting.internal.dto.JournalEntryCreateRequest;
import com.positivity.accounting.internal.dto.JournalEntryResponse;
import com.positivity.accounting.internal.entity.GLAccount;
import com.positivity.accounting.internal.entity.JournalEntryLine;
import com.positivity.accounting.internal.enums.AccountSubtype;
import com.positivity.accounting.internal.enums.AccountType;
import com.positivity.accounting.internal.exception.ReconciliationLineIneligibleException;
import com.positivity.accounting.internal.repository.GLAccountRepository;
import com.positivity.accounting.internal.repository.JournalEntryLineRepository;
import com.positivity.accounting.internal.service.JournalEntryService;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
 * The reconciliation core on the real baseline and seed (SPEC-manual-bank-reconciliation §4.2, §4.6, §8.1–§8.3;
 * story S4, #2303): residual settlement never leaves a line unexplained (criterion 11), the gap bridge through
 * the seeded {@code OTHER} → 2360 mapping and its reversal (criterion 12), adjustment replay posts one entry
 * (criterion 9), a reversed {@code ADJUSTMENT}-matched fee is a reversal pair while its bank row returns
 * (criterion 5), and a match racing a registration on the same line lets exactly one win (O1). Each test works
 * on accounts of its own, commits, and removes what it wrote. Requires Docker.
 */
@DisplayName("Bank reconciliation workflow on Postgres (#2303)")
class BankReconciliationWorkflowPostgresIT extends PostgresTenancyTestBase {

    private static final String ACK = "First statement reconciled on this account";
    private static final String WHY = "Rounding on the card processor's payout";
    private static final String ADJUST = "accounting:reconciliation:adjust";
    private static final String APPROVE = "accounting:reconciliation:approve";

    @Autowired
    private GLAccountRepository glAccounts;

    @Autowired
    private JournalEntryService journalEntries;

    @Autowired
    private JournalEntryLineRepository lines;

    @Autowired
    private BankStatementService statementService;

    @Autowired
    private BankTransactionRepository bankTransactions;

    @Autowired
    private BankReconciliationService reconciliationService;

    @Autowired
    private ReconciliationMatchingService matching;

    @Autowired
    private ReconciliationOutstandingItemService itemService;

    @Autowired
    private ReconciliationAdjustmentService adjustmentService;

    @Autowired
    private ReconciliationReviewService reviewService;

    @Autowired
    private BankReconciliationMatchRepository matchRepository;

    @Autowired
    private BankReconciliationOutstandingItemRepository itemRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private final List<UUID> accounts = new ArrayList<>();

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
        JdbcTemplate owner = new JdbcTemplate(ownerDataSource());
        for (UUID account : accounts) {
            String recons = "SELECT reconciliation_id FROM bank_reconciliation WHERE gl_account_id = '" + account + "'";
            owner.update("DELETE FROM bank_reconciliation_adjustment WHERE reconciliation_id IN (" + recons + ")");
            owner.update("DELETE FROM bank_reconciliation_outstanding_item WHERE gl_account_id = ?", account);
            owner.update("DELETE FROM bank_reconciliation_gl_match WHERE reconciliation_id IN (" + recons + ")");
            owner.update("DELETE FROM bank_reconciliation_bank_match WHERE match_id IN (SELECT match_id FROM"
                    + " bank_reconciliation_match WHERE reconciliation_id IN (" + recons + "))");
            owner.update("UPDATE bank_reconciliation_match SET replaces_match_id = NULL WHERE reconciliation_id IN ("
                    + recons + ")");
            owner.update("DELETE FROM bank_reconciliation_match WHERE reconciliation_id IN (" + recons + ")");
            owner.update("DELETE FROM bank_reconciliation WHERE gl_account_id = ?", account);
            owner.update("DELETE FROM bank_transaction WHERE gl_account_id = ?", account);
            owner.update("DELETE FROM bank_statement WHERE gl_account_id = ?", account);
            owner.update("DELETE FROM bank_account_profile WHERE gl_account_id = ?", account);
        }
        for (UUID account : accounts) {
            List<UUID> entries = owner.queryForList(
                    "SELECT DISTINCT journal_entry_id FROM journal_entry_line WHERE gl_account_id = ?",
                    UUID.class,
                    account);
            for (UUID entry : entries) {
                owner.update(
                        "UPDATE journal_entry SET reversal_journal_entry_id = NULL, reversed_by_journal_entry_id = NULL"
                                + " WHERE journal_entry_id = ?",
                        entry);
            }
            for (UUID entry : entries) {
                owner.update("DELETE FROM journal_entry_line WHERE journal_entry_id = ?", entry);
                owner.update("DELETE FROM journal_entry WHERE journal_entry_id = ?", entry);
            }
        }
        for (UUID account : accounts) {
            owner.update("DELETE FROM gl_account WHERE gl_account_id = ?", account);
        }
        accounts.clear();
    }

    // ---- criterion 11 ----------------------------------------------------------------------------

    @Test
    @DisplayName("five 99.99 / 100.00 residuals settle to a zero difference, never leaving a line unexplained [M]")
    void residualSettlement() {
        UUID cash = bankAccount();
        UUID revenue = otherAccount();
        LocalDate day = LocalDate.of(2021, 3, 10);
        List<UUID> glLines = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            glLines.add(cashLine(post(cash, revenue, "100.00", day), cash));
        }
        UUID statementId = statement(
                cash,
                "2021-03-01",
                "2021-03-31",
                "0",
                "499.95",
                ACK,
                List.of("99.99", "99.99", "99.99", "99.99", "99.99"),
                day);
        List<UUID> bankIds = bankRows(statementId);
        as(ADJUST);
        UUID reconId = create(cash, statementId).getReconciliationId();
        assertThat(get(reconId).getDifference()).isEqualByComparingTo("-0.05");

        List<UUID> matchIds = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            ReconciliationMatchResponse match = match(reconId, List.of(bankIds.get(i)), List.of(glLines.get(i)), WHY);
            assertThat(match.getToleranceUsed()).isEqualByComparingTo("0.01");
            assertThat(match.getResidual()).isEqualByComparingTo("-0.01");
            matchIds.add(match.getMatchId());
        }
        for (UUID matchId : matchIds) {
            BankReconciliationAdjustmentResponse settled = inTx(() -> adjustmentService.addAdjustment(
                    reconId,
                    ReconciliationAdjustmentRequest.builder()
                            .type(BankAdjustmentType.OTHER)
                            .settlesMatchId(matchId)
                            .justification(WHY)
                            .requestId(UUIDv7Generator.generate())
                            .build()));
            assertThat(settled.getAmount()).isEqualByComparingTo("-0.01");
            BankReconciliationResponse after = get(reconId);
            assertThat(after.getCountUnexplainedLedger())
                    .as("no line is unexplained at any commit")
                    .isZero();
            assertThat(after.getCountUnexplainedBank()).isZero();
            inTx(() -> {
                assertThat(matchRepository.findById(matchId).orElseThrow().getUnmatchReason())
                        .isEqualTo("RESIDUAL_SETTLED");
                assertThat(matchRepository.findById(settled.getMatchId()).orElseThrow())
                        .satisfies(replacement -> {
                            assertThat(replacement.getState()).isEqualTo(MatchState.ACCEPTED);
                            assertThat(replacement.getMatchKind()).isEqualTo(MatchKind.ONE_TO_ONE);
                            assertThat(replacement.getReplacesMatchId()).isEqualTo(matchId);
                            assertThat(replacement.getToleranceUsed()).isEqualByComparingTo("0");
                        });
                return null;
            });
        }
        assertThat(get(reconId).getDifference()).isEqualByComparingTo("0");
    }

    // ---- criterion 12 ----------------------------------------------------------------------------

    @Test
    @DisplayName("a gap bridge posts the opening difference to 2360, refuses a second, and returns once reversed")
    void gapBridge() {
        UUID cash = bankAccount();
        UUID revenue = otherAccount();
        post(cash, revenue, "1045.67", LocalDate.of(2021, 4, 20));
        LocalDate day = LocalDate.of(2021, 5, 10);
        UUID deposit = cashLine(post(cash, revenue, "200.00", day), cash);
        UUID statementId =
                statement(cash, "2021-05-01", "2021-05-31", "1000.00", "1200.00", ACK, List.of("200.00"), day);
        as(ADJUST, APPROVE);
        UUID reconId = create(cash, statementId).getReconciliationId();
        match(reconId, bankRows(statementId), List.of(deposit), null);
        BankReconciliationResponse before = get(reconId);
        assertThat(before.getOpeningDifference()).isEqualByComparingTo("-45.67");
        assertThat(before.getDifference()).isEqualByComparingTo("-45.67");
        ReconciliationReviewResponse review = inTx(() -> reviewService.review(reconId));
        assertThat(review.getDiagnostics().getFlags()).containsExactly("OPENING_DIFFERENCE");
        assertThat(review.getDiagnostics().getLikelyCause()).isEqualTo("GAP_NOT_BRIDGED");

        BankReconciliationAdjustmentResponse bridge =
                inTx(() -> adjustmentService.addAdjustment(reconId, bridge(statementId)));
        assertThat(bridge.getAmount()).isEqualByComparingTo("-45.67");
        assertThat(bridge.getTransactionDate()).isEqualTo(LocalDate.of(2021, 4, 30));
        BankReconciliationResponse after = get(reconId);
        assertThat(after.getOpeningDifference()).isEqualByComparingTo("0");
        assertThat(after.getDifference()).isEqualByComparingTo("0");
        assertThat(after.getCountUnexplainedLedger())
                .as("the bridge's cash line is explained")
                .isZero();

        assertThatThrownBy(() -> inTx(() -> adjustmentService.addAdjustment(reconId, bridge(statementId))))
                .isInstanceOfSatisfying(
                        BankRecException.class,
                        e -> assertThat(e.code()).isEqualTo(BankRecErrorCode.ADJUSTMENT_BRIDGE_ALREADY_POSTED));

        inTx(() -> adjustmentService.reverse(
                reconId,
                bridge.getAdjustmentId(),
                new AdjustmentReverseRequest("Bridge posted to the wrong date", null, null)));
        BankReconciliationResponse reversed = get(reconId);
        assertThat(reversed.getOpeningDifference()).isEqualByComparingTo("-45.67");
        assertThat(reversed.getCountUnexplainedLedger())
                .as("a reversed bridge is a reversal pair")
                .isZero();
        assertThat(inTx(() -> adjustmentService.addAdjustment(reconId, bridge(statementId)))
                        .getAmount())
                .isEqualByComparingTo("-45.67");
    }

    // ---- criteria 5 and 9 ------------------------------------------------------------------------

    @Test
    @DisplayName("a replayed fee posts one entry; reversed, its pair never counts and its bank row returns [M]")
    void feeReplayAndReversalPair() {
        UUID cash = bankAccount();
        UUID revenue = otherAccount();
        LocalDate day = LocalDate.of(2021, 6, 12);
        UUID deposit = cashLine(post(cash, revenue, "500.00", day), cash);
        UUID statementId =
                statement(cash, "2021-06-01", "2021-06-30", "0", "485.00", ACK, List.of("500.00", "-15.00"), day);
        List<UUID> bankIds = bankRows(statementId);
        as(ADJUST, APPROVE);
        UUID reconId = create(cash, statementId).getReconciliationId();
        match(reconId, List.of(bankIds.get(0)), List.of(deposit), null);

        ReconciliationAdjustmentRequest fee = ReconciliationAdjustmentRequest.builder()
                .type(BankAdjustmentType.BANK_FEE)
                .amount(new BigDecimal("-15.00"))
                .bankTransactionId(bankIds.get(1))
                .requestId(UUIDv7Generator.generate())
                .build();
        BankReconciliationAdjustmentResponse first = inTx(() -> adjustmentService.addAdjustment(reconId, fee));
        BankReconciliationAdjustmentResponse replay = inTx(() -> adjustmentService.addAdjustment(reconId, fee));
        assertThat(replay.isReplayed()).isTrue();
        assertThat(replay.getJournalEntryId()).isEqualTo(first.getJournalEntryId());
        assertThat(inTx(() -> journalEntries.findOriginalBySourceEvent(
                                ReconciliationAdjustmentServiceImpl.sourceEventId(first.getAdjustmentId())))
                        .map(JournalEntryResponse::getJournalEntryId))
                .contains(first.getJournalEntryId());
        BankReconciliationResponse matched = get(reconId);
        assertThat(matched.getDifference()).isEqualByComparingTo("0");
        assertThat(matched.getCountUnexplainedBank()).isZero();
        assertThat(matched.getCountUnexplainedLedger()).isZero();

        inTx(() -> adjustmentService.reverse(
                reconId, first.getAdjustmentId(), new AdjustmentReverseRequest("Bank refunded the fee", null, null)));
        BankReconciliationResponse reversed = get(reconId);
        assertThat(reversed.getCountUnexplainedLedger())
                .as("neither line of the pair counts")
                .isZero();
        assertThat(reversed.getCountUnexplainedBank())
                .as("the fee's bank row is back")
                .isEqualTo(1);
        assertThat(reversed.getDifference()).isEqualByComparingTo("-15.00");
        assertThat(inTx(() ->
                        bankTransactions.findById(bankIds.get(1)).orElseThrow().getStatus()))
                .isEqualTo(BankTransactionStatus.UNMATCHED);
    }

    // ---- O1 / U4 ---------------------------------------------------------------------------------

    @Test
    @DisplayName("a match racing a registration on one line never leaves it both matched and in an OPEN item (O1)")
    void matchRacesRegistration() throws Exception {
        UUID cash = bankAccount();
        UUID revenue = otherAccount();
        LocalDate day = LocalDate.of(2021, 7, 30);
        UUID line = cashLine(post(cash, revenue, "300.00", day), cash);
        UUID statementId = statement(cash, "2021-07-01", "2021-07-31", "0", "300.00", ACK, List.of("300.00"), day);
        UUID bankId = bankRows(statementId).get(0);
        as(ADJUST);
        UUID reconId = create(cash, statementId).getReconciliationId();

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Boolean> outcomes;
        try {
            Future<Boolean> matchWon =
                    pool.submit(() -> race(start, () -> match(reconId, List.of(bankId), List.of(line), null)));
            Future<Boolean> itemWon = pool.submit(() -> race(
                    start,
                    () -> inTx(() -> itemService.register(
                            reconId,
                            OutstandingItemRegisterRequest.builder()
                                    .glLineId(line)
                                    .itemKind(OutstandingItemKind.DEPOSIT_IN_TRANSIT)
                                    .build()))));
            start.countDown();
            outcomes = List.of(matchWon.get(60, TimeUnit.SECONDS), itemWon.get(60, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
        assertThat(outcomes).contains(true);
        boolean matched = inTx(() -> matchRepository.findByReconciliationIdOrderByCreatedAtAsc(reconId)).stream()
                .anyMatch(m -> m.getState() == MatchState.ACCEPTED);
        boolean open = !inTx(() -> itemRepository.findByGlLineIdInAndStatus(List.of(line), OutstandingItemStatus.OPEN))
                .isEmpty();
        assertThat(matched && open).as("O1: never both").isFalse();
        if (outcomes.equals(List.of(true, true))) {
            // The registration committed first and the match cleared it in the same transaction (§5.4).
            assertThat(inTx(() ->
                            itemRepository.findByGlLineIdInAndStatus(List.of(line), OutstandingItemStatus.CLEARED)))
                    .singleElement()
                    .satisfies(item -> assertThat(item.getClosedOn()).isEqualTo(day));
        }
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private boolean race(CountDownLatch start, Callable<?> work) throws InterruptedException {
        start.await();
        SecurityContextHolder.getContext().setAuthentication(token(ADJUST));
        try {
            work.call();
            return true;
        } catch (ReconciliationLineIneligibleException | BankRecException e) {
            return false;
        } catch (RuntimeException e) {
            if (e.getClass().getSimpleName().contains("Optimistic")
                    || e.getClass().getSimpleName().contains("CannotAcquireLock")) {
                return false;
            }
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private static UsernamePasswordAuthenticationToken token(String... authorities) {
        UsernamePasswordAuthenticationToken token = new UsernamePasswordAuthenticationToken(
                "preparer",
                null,
                java.util.Arrays.stream(authorities)
                        .map(SimpleGrantedAuthority::new)
                        .toList());
        token.setDetails(Map.of());
        return token;
    }

    private void as(String... authorities) {
        SecurityContextHolder.getContext().setAuthentication(token(authorities));
    }

    private ReconciliationAdjustmentRequest bridge(UUID statementId) {
        return ReconciliationAdjustmentRequest.builder()
                .type(BankAdjustmentType.OTHER)
                .bridgesStatementId(statementId)
                .justification("Gap left by the change of bank")
                .requestId(UUIDv7Generator.generate())
                .build();
    }

    private BankReconciliationResponse create(UUID cash, UUID statementId) {
        return inTx(() -> reconciliationService.create(ReconciliationCreateRequest.builder()
                .glAccountId(cash)
                .requestId(UUIDv7Generator.generate())
                .statementId(statementId)
                .build()));
    }

    private BankReconciliationResponse get(UUID reconId) {
        return inTx(() -> reconciliationService.get(reconId));
    }

    private ReconciliationMatchResponse match(UUID reconId, List<UUID> bankIds, List<UUID> glIds, String why) {
        return inTx(() -> matching.createMatch(
                reconId,
                ReconciliationMatchCreateRequest.builder()
                        .bankTransactionIds(bankIds)
                        .glLineIds(glIds)
                        .justification(why)
                        .requestId(UUIDv7Generator.generate())
                        .build()));
    }

    private UUID statement(
            UUID account,
            String start,
            String end,
            String opening,
            String closing,
            String ack,
            List<String> amounts,
            LocalDate rowDate) {
        List<BankStatementCreateRequest.Transaction> rows = new ArrayList<>();
        int n = 0;
        for (String amount : amounts) {
            rows.add(BankStatementCreateRequest.Transaction.builder()
                    .date(rowDate)
                    .signedAmount(new BigDecimal(amount))
                    .description("ROW " + (++n))
                    .build());
        }
        return inTx(() -> statementService
                .createManualStatement(BankStatementCreateRequest.builder()
                        .glAccountId(account)
                        .requestId(UUIDv7Generator.generate())
                        .statement(BankStatementCreateRequest.Header.builder()
                                .startDate(LocalDate.parse(start))
                                .endDate(LocalDate.parse(end))
                                .openingBalance(new BigDecimal(opening))
                                .closingBalance(new BigDecimal(closing))
                                .build())
                        .transactions(rows)
                        .gapAcknowledgement(ack)
                        .build())
                .getStatementId());
    }

    private List<UUID> bankRows(UUID statementId) {
        return inTx(() -> bankTransactions.findByStatementIdOrderBySourceRowNumberAsc(statementId).stream()
                .map(BankTransaction::getBankTransactionId)
                .toList());
    }

    private UUID bankAccount() {
        return account(AccountType.ASSET, AccountSubtype.BANK_CASH, true);
    }

    private UUID otherAccount() {
        return account(AccountType.REVENUE, null, false);
    }

    private UUID account(AccountType type, AccountSubtype subtype, boolean reconcilable) {
        String suffix = UUIDv7Generator.generate().toString().substring(24);
        UUID id = inTx(() -> {
            GLAccount account = new GLAccount();
            account.setGlAccountId(UUIDv7Generator.generate());
            account.setAccountCode("W" + suffix);
            account.setAccountName("Workflow IT " + suffix);
            account.setAccountType(type);
            account.setAccountSubtype(subtype);
            account.setReconcilable(reconcilable);
            account.setActivationDate(LocalDateTime.of(2020, 1, 1, 0, 0));
            account.setCreatedBy("it");
            account.setModifiedBy("it");
            return glAccounts.save(account).getGlAccountId();
        });
        accounts.add(id);
        return id;
    }

    /** Posts Dr cash / Cr counter of {@code amount} on {@code day}; returns the entry id. */
    private UUID post(UUID cash, UUID counter, String amount, LocalDate day) {
        BigDecimal value = new BigDecimal(amount);
        return inTx(() -> {
            JournalEntryResponse created = journalEntries.createJournalEntry(JournalEntryCreateRequest.builder()
                    .transactionDate(day.atTime(12, 0))
                    .sourceEventId(UUIDv7Generator.generate())
                    .description("Workflow IT receipt")
                    .lines(List.of(
                            JournalEntryCreateRequest.JournalEntryLineRequest.builder()
                                    .glAccountId(cash)
                                    .debitAmount(value)
                                    .creditAmount(BigDecimal.ZERO)
                                    .build(),
                            JournalEntryCreateRequest.JournalEntryLineRequest.builder()
                                    .glAccountId(counter)
                                    .debitAmount(BigDecimal.ZERO)
                                    .creditAmount(value)
                                    .build()))
                    .build());
            return journalEntries
                    .postJournalEntry(created.getJournalEntryId(), null)
                    .getJournalEntryId();
        });
    }

    private UUID cashLine(UUID entryId, UUID cash) {
        return inTx(() -> lines.findByJournalEntry_JournalEntryId(entryId).stream()
                .filter(l -> cash.equals(l.getGlAccountId()))
                .map(JournalEntryLine::getLineId)
                .findFirst()
                .orElseThrow());
    }

    private <T> T inTx(Callable<T> work) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        return asTenant(
                TENANT_A,
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
}
