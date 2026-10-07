package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.bankrec.readmodel.BankAccountCurrencies;
import com.positivity.accounting.internal.bankrec.readmodel.BankStatementCoverage;
import com.positivity.accounting.internal.config.LedgerCurrency;
import com.positivity.accounting.internal.dto.BankOpeningBalanceRequest;
import com.positivity.accounting.internal.dto.BankOpeningBalanceResponse;
import com.positivity.accounting.internal.dto.JournalEntryCreateRequest;
import com.positivity.accounting.internal.dto.JournalEntryResponse;
import com.positivity.accounting.internal.entity.AccountingAuditLog;
import com.positivity.accounting.internal.entity.BankOpeningBalance;
import com.positivity.accounting.internal.entity.GLAccount;
import com.positivity.accounting.internal.entity.JournalEntryLine;
import com.positivity.accounting.internal.enums.AccountSubtype;
import com.positivity.accounting.internal.enums.BankOpeningItemType;
import com.positivity.accounting.internal.exception.AccountingTimeZoneUnsetException;
import com.positivity.accounting.internal.exception.CashSetupException;
import com.positivity.accounting.internal.exception.CurrencyNotSupportedException;
import com.positivity.accounting.internal.exception.InvalidRequestParameterException;
import com.positivity.accounting.internal.repository.AccountingAuditLogRepository;
import com.positivity.accounting.internal.repository.BankOpeningBalanceRepository;
import com.positivity.accounting.internal.repository.GLAccountRepository;
import com.positivity.accounting.internal.repository.JournalEntryLineRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@DisplayName("Bank opening balance command (#2572, OI-10)")
class BankOpeningBalanceServiceImplTest {

    private static final UUID BANK = UUID.fromString("019a0000-0000-7000-8000-000000001000");
    private static final UUID EQUITY = UUID.fromString("019a0000-0000-7000-8000-000000003900");
    private static final LocalDate AS_OF = LocalDate.of(2025, 10, 31);
    private static final String WHY = "Opening balance per the October bank statement";

    private final BankOpeningBalanceRepository openings = mock(BankOpeningBalanceRepository.class);
    private final GLAccountRepository glAccounts = mock(GLAccountRepository.class);
    private final JournalEntryLineRepository journalLines = mock(JournalEntryLineRepository.class);
    private final GLMappingResolver resolver = mock(GLMappingResolver.class);
    private final JournalEntryService journalEntries = mock(JournalEntryService.class);
    private final AccountingAuditLogRepository auditLogs = mock(AccountingAuditLogRepository.class);
    private final AccountingCalendarZoneResolver zoneResolver = mock(AccountingCalendarZoneResolver.class);
    private final BankAccountCurrencies currencies = mock(BankAccountCurrencies.class);
    private final BankStatementCoverage coverage = mock(BankStatementCoverage.class);
    private final Map<UUID, JournalEntryResponse> entries = new HashMap<>();
    private final Map<UUID, List<JournalEntryLine>> entryLines = new HashMap<>();
    private final List<BankOpeningBalance> saved = new ArrayList<>();
    private GLAccount bank;
    private BankOpeningBalanceServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new BankOpeningBalanceServiceImpl(
                openings,
                glAccounts,
                journalLines,
                resolver,
                journalEntries,
                auditLogs,
                zoneResolver,
                new LedgerCurrency("USD"),
                currencies,
                coverage);

        bank = new GLAccount(BANK);
        bank.setAccountCode("1000");
        bank.setAccountSubtype(AccountSubtype.BANK_CASH);
        bank.setActivationDate(LocalDateTime.of(2020, 1, 1, 0, 0));
        when(glAccounts.lockById(BANK)).thenAnswer(invocation -> Optional.ofNullable(bank));
        when(glAccounts.findById(BANK)).thenAnswer(invocation -> Optional.ofNullable(bank));
        when(currencies.currencyOf(BANK)).thenReturn(Optional.of("USD"));
        when(zoneResolver.today()).thenReturn(LocalDate.of(2025, 11, 1));
        when(openings.findByRequestId(any())).thenReturn(Optional.empty());
        when(openings.findStandingByGlAccountId(BANK)).thenReturn(List.of());
        when(openings.saveAndFlush(any())).thenAnswer(invocation -> {
            BankOpeningBalance row = invocation.getArgument(0);
            row.setBankOpeningBalanceId(UUID.randomUUID());
            saved.add(row);
            return row;
        });
        when(resolver.resolveGLAccount(eq("OPENING_BALANCE"), eq("OPENING_BALANCE_EQUITY"), any(LocalDateTime.class)))
                .thenReturn(EQUITY);
        // The created entry echoes the request's lines, numbered, each with its own id.
        when(journalEntries.createJournalEntry(any())).thenAnswer(invocation -> {
            JournalEntryCreateRequest request = invocation.getArgument(0);
            List<JournalEntryResponse.JournalEntryLineResponse> lines = new ArrayList<>();
            int number = 0;
            for (JournalEntryCreateRequest.JournalEntryLineRequest line : request.getLines()) {
                JournalEntryResponse.JournalEntryLineResponse response =
                        new JournalEntryResponse.JournalEntryLineResponse();
                response.setLineId(UUID.randomUUID());
                response.setLineNumber(++number);
                response.setGlAccountId(line.getGlAccountId());
                response.setDebitAmount(line.getDebitAmount());
                response.setCreditAmount(line.getCreditAmount());
                response.setDimensions(line.getDimensions());
                lines.add(response);
            }
            List<JournalEntryLine> persisted = new ArrayList<>();
            for (JournalEntryResponse.JournalEntryLineResponse line : lines) {
                JournalEntryLine entity = new JournalEntryLine();
                entity.setLineId(line.getLineId());
                entity.setLineNumber(line.getLineNumber());
                entity.setGlAccountId(line.getGlAccountId());
                entity.setDebitAmount(line.getDebitAmount());
                entity.setCreditAmount(line.getCreditAmount());
                entity.setDimensions(line.getDimensions());
                persisted.add(entity);
            }
            JournalEntryResponse entry = JournalEntryResponse.builder()
                    .journalEntryId(UUID.randomUUID())
                    .entryNumber("JE-202510-00000" + (entries.size() + 1))
                    .lines(lines)
                    .build();
            entries.put(entry.getJournalEntryId(), entry);
            entryLines.put(entry.getJournalEntryId(), persisted);
            return entry;
        });
        when(journalEntries.postJournalEntry(any(UUID.class), isNull()))
                .thenAnswer(invocation -> entries.get(invocation.<UUID>getArgument(0)));
        when(journalEntries.getJournalEntry(any()))
                .thenAnswer(invocation -> entries.get(invocation.<UUID>getArgument(0)));
        when(journalLines.findByJournalEntry_JournalEntryId(any()))
                .thenAnswer(invocation -> entryLines.get(invocation.<UUID>getArgument(0)));
    }

    @Test
    @DisplayName("AC1/AC7: the worked example posts Dr 1000 10,000 / Cr 1000 450 / Dr 1000 1,200 / Cr 3900 10,750"
            + " dated asOfDate, with no override, and the book balance is 10,750")
    void workedExample() {
        BankOpeningBalanceService.Outcome outcome = service.establish(BANK, workedExample(UUID.randomUUID()));

        JournalEntryCreateRequest entry = onlyEntry();
        assertThat(entry.getTransactionDate()).isEqualTo(AS_OF.atStartOfDay());
        assertThat(entry.getSourceEventType()).isEqualTo("OPENING_BALANCE");
        assertThat(entry.getLines())
                .extracting(
                        JournalEntryCreateRequest.JournalEntryLineRequest::getGlAccountId,
                        l -> l.getDebitAmount().toPlainString(),
                        l -> l.getCreditAmount().toPlainString())
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(BANK, "10000.00", "0"),
                        org.assertj.core.groups.Tuple.tuple(BANK, "0", "450.00"),
                        org.assertj.core.groups.Tuple.tuple(BANK, "1200.00", "0"),
                        org.assertj.core.groups.Tuple.tuple(EQUITY, "0", "10750.00"));
        assertThat(entry.getLines().get(0).getDimensions()).isNull();
        assertThat(entry.getLines().get(1).getDimensions())
                .containsEntry("outstandingItemType", "OUTSTANDING_CHECK")
                .containsEntry("reference", "1043")
                .containsEntry("itemDate", "2025-10-28");
        assertThat(entry.getLines().get(2).getDimensions())
                .containsEntry("outstandingItemType", "DEPOSIT_IN_TRANSIT")
                .containsEntry("reference", "DEP-1031")
                .containsEntry("itemDate", "2025-10-31");
        // No override path (§4.6 "in an open period", as the go-live float).
        verify(journalEntries).postJournalEntry(any(UUID.class), isNull());

        BankOpeningBalanceResponse response = outcome.response();
        assertThat(outcome.replayed()).isFalse();
        assertThat(response.accountCode()).isEqualTo("1000");
        assertThat(response.statementBalance()).isEqualByComparingTo("10000.00");
        assertThat(response.bookBalance()).isEqualByComparingTo("10750.00");
        assertThat(response.currencyCode()).isEqualTo("USD");
        assertThat(response.journalEntryNumber()).isEqualTo("JE-202510-000001");
        List<JournalEntryResponse.JournalEntryLineResponse> posted =
                entries.get(response.journalEntryId()).getLines();
        assertThat(response.outstandingItems())
                .extracting(
                        BankOpeningBalanceResponse.Item::type,
                        BankOpeningBalanceResponse.Item::reference,
                        BankOpeningBalanceResponse.Item::itemDate,
                        i -> i.amount().toPlainString(),
                        BankOpeningBalanceResponse.Item::glLineId)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                BankOpeningItemType.OUTSTANDING_CHECK,
                                "1043",
                                LocalDate.of(2025, 10, 28),
                                "450.00",
                                posted.get(1).getLineId()),
                        org.assertj.core.groups.Tuple.tuple(
                                BankOpeningItemType.DEPOSIT_IN_TRANSIT,
                                "DEP-1031",
                                AS_OF,
                                "1200.00",
                                posted.get(2).getLineId()));

        assertThat(saved).singleElement().satisfies(row -> {
            assertThat(row.getGlAccountId()).isEqualTo(BANK);
            assertThat(row.getAsOfDate()).isEqualTo(AS_OF);
            assertThat(row.getBookBalance()).isEqualByComparingTo("10750.00");
            assertThat(row.getCurrencyCode()).isEqualTo("USD");
            assertThat(row.getJustification()).isEqualTo(WHY);
            assertThat(row.getActor()).isEqualTo("SYSTEM");
        });
        ArgumentCaptor<AccountingAuditLog> audit = ArgumentCaptor.forClass(AccountingAuditLog.class);
        verify(auditLogs).save(audit.capture());
        assertThat(audit.getValue().getOperation()).isEqualTo("BANK_OPENING_BALANCE_ESTABLISH");
        assertThat(audit.getValue().getEntityType()).isEqualTo("BANK_OPENING_BALANCE");
        assertThat(audit.getValue().getJustification()).isEqualTo(WHY);
    }

    @Test
    @DisplayName("AC1: an overdrawn account credits the bank and debits 3900")
    void overdrawnAccount() {
        BankOpeningBalanceService.Outcome outcome =
                service.establish(BANK, request("-300.00", List.of(), UUID.randomUUID()));

        assertThat(onlyEntry().getLines())
                .extracting(
                        JournalEntryCreateRequest.JournalEntryLineRequest::getGlAccountId,
                        l -> l.getDebitAmount().toPlainString(),
                        l -> l.getCreditAmount().toPlainString())
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(BANK, "0", "300.00"),
                        org.assertj.core.groups.Tuple.tuple(EQUITY, "300.00", "0"));
        assertThat(outcome.response().bookBalance()).isEqualByComparingTo("-300.00");
    }

    @Test
    @DisplayName("a zero statement balance posts no statement line, and items that net to it post no 3900 line")
    void zeroAmountsPostNoLine() {
        service.establish(
                BANK,
                request(
                        "0",
                        List.of(
                                item(BankOpeningItemType.OUTSTANDING_CHECK, "77", "100.00"),
                                item(BankOpeningItemType.DEPOSIT_IN_TRANSIT, "D-9", "100.00")),
                        UUID.randomUUID()));

        assertThat(onlyEntry().getLines())
                .extracting(
                        l -> l.getCreditAmount().toPlainString(),
                        l -> l.getDebitAmount().toPlainString())
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("100.00", "0"),
                        org.assertj.core.groups.Tuple.tuple("0", "100.00"));
    }

    @Test
    @DisplayName("AC3: a missing, non-bank, inactive or foreign-currency account is 422"
            + " BANK_OPENING_BALANCE_ACCOUNT_NOT_ELIGIBLE and posts nothing")
    void accountMustBeEligible() {
        GLAccount found = bank;
        bank = null;
        assertRefused(CashSetupException.Code.BANK_OPENING_BALANCE_ACCOUNT_NOT_ELIGIBLE);

        bank = found;
        bank.setAccountSubtype(AccountSubtype.CASH_ON_HAND);
        assertRefused(CashSetupException.Code.BANK_OPENING_BALANCE_ACCOUNT_NOT_ELIGIBLE);

        bank.setAccountSubtype(AccountSubtype.BANK_CASH);
        bank.setDeactivationDate(AS_OF.atStartOfDay());
        assertRefused(CashSetupException.Code.BANK_OPENING_BALANCE_ACCOUNT_NOT_ELIGIBLE);

        bank.setDeactivationDate(null);
        when(currencies.currencyOf(BANK)).thenReturn(Optional.of("CAD"));
        assertRefused(CashSetupException.Code.BANK_OPENING_BALANCE_ACCOUNT_NOT_ELIGIBLE);
    }

    @Test
    @DisplayName("ADR-0067: a missing currencyCode, or one not on the ISO 4217 list, is 400 VALIDATION_ERROR")
    void currencyCodeIsRequiredAndIso() {
        for (String code : new String[] {null, " ", "XYZ", "usd", "DOLLARS"}) {
            BankOpeningBalanceRequest body = new BankOpeningBalanceRequest(
                    AS_OF, new BigDecimal("10.00"), code, List.of(), WHY, UUID.randomUUID());
            assertThatThrownBy(() -> service.establish(BANK, body))
                    .as("currencyCode %s", code)
                    .isInstanceOfSatisfying(
                            InvalidRequestParameterException.class,
                            e -> assertThat(e.getField()).isEqualTo("currencyCode"));
        }
        verify(journalEntries, never()).createJournalEntry(any());
    }

    @Test
    @DisplayName("ADR-0067: an ISO code other than the bank account's currency is 422"
            + " CURRENCY_NOT_SUPPORTED (PC-9) and posts nothing")
    void currencyCodeMustBeTheAccounts() {
        BankOpeningBalanceRequest euros =
                new BankOpeningBalanceRequest(AS_OF, new BigDecimal("10.00"), "EUR", List.of(), WHY, UUID.randomUUID());
        assertThatThrownBy(() -> service.establish(BANK, euros))
                .isInstanceOf(CurrencyNotSupportedException.class)
                .hasMessageContaining("EUR");
        verify(journalEntries, never()).createJournalEntry(any());
        assertThat(saved).isEmpty();
    }

    @Test
    @DisplayName("AC4: a standing opening is 409 BANK_OPENING_BALANCE_ALREADY_ESTABLISHED and posts nothing")
    void oncePerAccount() {
        when(openings.findStandingByGlAccountId(BANK)).thenReturn(List.of(new BankOpeningBalance()));

        assertRefused(CashSetupException.Code.BANK_OPENING_BALANCE_ALREADY_ESTABLISHED);
    }

    @Test
    @DisplayName("AC5: a standing posted line on or before asOfDate, or a committed statement starting on or before"
            + " it, is 422 BANK_OPENING_BALANCE_NOT_FIRST")
    void openingMustComeFirst() {
        LocalDateTime dayAfter = AS_OF.plusDays(1).atStartOfDay();
        when(journalLines.countStandingPostedLinesBefore(BANK, dayAfter)).thenReturn(1L);
        assertRefused(CashSetupException.Code.BANK_OPENING_BALANCE_NOT_FIRST);

        when(journalLines.countStandingPostedLinesBefore(BANK, dayAfter)).thenReturn(0L);
        when(coverage.startsOnOrBefore(BANK, AS_OF)).thenReturn(true);
        assertRefused(CashSetupException.Code.BANK_OPENING_BALANCE_NOT_FIRST);

        // Lines dated after asOfDate are allowed: the bound is the start of the next day.
        when(coverage.startsOnOrBefore(BANK, AS_OF)).thenReturn(false);
        assertThat(service.establish(BANK, workedExample(UUID.randomUUID())).replayed())
                .isFalse();
        verify(journalLines, org.mockito.Mockito.atLeastOnce()).countStandingPostedLinesBefore(BANK, dayAfter);
    }

    @Test
    @DisplayName("AC5: a zero balance with no items is 422 BANK_OPENING_BALANCE_EMPTY")
    void emptyOpeningIsRefused() {
        assertThatThrownBy(() -> service.establish(BANK, request("0.00", List.of(), UUID.randomUUID())))
                .isInstanceOfSatisfying(
                        CashSetupException.class,
                        e -> assertThat(e.getCode()).isEqualTo(CashSetupException.Code.BANK_OPENING_BALANCE_EMPTY));
        verify(journalEntries, never()).createJournalEntry(any());
    }

    @Test
    @DisplayName("AC2: asOfDate after today (in the accounting time zone) or an itemDate after asOfDate is 400")
    void datesAreChecked() {
        when(zoneResolver.today()).thenReturn(AS_OF.minusDays(1));
        assertThatThrownBy(() -> service.establish(BANK, workedExample(UUID.randomUUID())))
                .isInstanceOfSatisfying(
                        InvalidRequestParameterException.class,
                        e -> assertThat(e.getField()).isEqualTo("asOfDate"))
                .hasMessageContaining("after today");

        when(zoneResolver.today()).thenReturn(AS_OF);
        BankOpeningBalanceRequest lateItem = request(
                "10000.00",
                List.of(new BankOpeningBalanceRequest.OutstandingItem(
                        BankOpeningItemType.OUTSTANDING_CHECK, "1044", AS_OF.plusDays(1), new BigDecimal("5.00"))),
                UUID.randomUUID());
        assertThatThrownBy(() -> service.establish(BANK, lateItem))
                .isInstanceOfSatisfying(
                        InvalidRequestParameterException.class,
                        e -> assertThat(e.getField()).isEqualTo("outstandingItems[0].itemDate"));
        verify(journalEntries, never()).createJournalEntry(any());
    }

    @Test
    @DisplayName("Dates: an unset accounting time zone fails closed and posts nothing")
    void unsetZoneFailsClosed() {
        when(zoneResolver.today()).thenThrow(new AccountingTimeZoneUnsetException());

        assertThatThrownBy(() -> service.establish(BANK, workedExample(UUID.randomUUID())))
                .isInstanceOf(AccountingTimeZoneUnsetException.class);
        verify(journalEntries, never()).createJournalEntry(any());
    }

    @Test
    @DisplayName("a body with a short justification, a non-positive item, a blank reference or sub-cent amounts is 400")
    void requestShapeIsValidated() {
        assertThatThrownBy(() -> service.establish(
                        BANK,
                        new BankOpeningBalanceRequest(
                                AS_OF, new BigDecimal("1.00"), "USD", List.of(), "too short", UUID.randomUUID())))
                .isInstanceOf(InvalidRequestParameterException.class);
        assertThatThrownBy(() -> service.establish(
                        BANK,
                        request(
                                "1.00",
                                List.of(item(BankOpeningItemType.OUTSTANDING_CHECK, "1", "0")),
                                UUID.randomUUID())))
                .isInstanceOf(InvalidRequestParameterException.class);
        assertThatThrownBy(() -> service.establish(
                        BANK,
                        request(
                                "1.00",
                                List.of(item(BankOpeningItemType.OUTSTANDING_CHECK, " ", "5.00")),
                                UUID.randomUUID())))
                .isInstanceOf(InvalidRequestParameterException.class);
        assertThatThrownBy(() -> service.establish(BANK, request("1.001", List.of(), UUID.randomUUID())))
                .isInstanceOf(InvalidRequestParameterException.class);
        assertThatThrownBy(() -> service.establish(
                        BANK,
                        request(
                                "1.00",
                                List.of(item(BankOpeningItemType.DEPOSIT_IN_TRANSIT, "D", "5.005")),
                                UUID.randomUUID())))
                .isInstanceOf(InvalidRequestParameterException.class);
        verify(journalEntries, never()).createJournalEntry(any());
    }

    @Test
    @DisplayName("AC9: the same requestId and body returns the first result; another body is 409 IDEMPOTENCY_CONFLICT")
    void idempotentOnRequestId() {
        UUID requestId = UUID.randomUUID();
        BankOpeningBalanceService.Outcome first = service.establish(BANK, workedExample(requestId));
        BankOpeningBalance row = saved.get(0);
        when(openings.findByRequestId(requestId)).thenReturn(Optional.of(row));
        // Whatever happened since (here: the opening now stands), a replay answers with the first result.
        when(openings.findStandingByGlAccountId(BANK)).thenReturn(List.of(row));

        BankOpeningBalanceService.Outcome replay = service.establish(BANK, workedExample(requestId));

        assertThat(replay.replayed()).isTrue();
        assertThat(replay.response().replayed()).isTrue();
        assertThat(replay.response().journalEntryId())
                .isEqualTo(first.response().journalEntryId());
        assertThat(replay.response().outstandingItems())
                .isEqualTo(first.response().outstandingItems());
        assertThat(entries).hasSize(1);

        BankOpeningBalanceRequest other = request("10001.00", List.of(), requestId);
        BankOpeningBalanceRequest otherCurrency = new BankOpeningBalanceRequest(
                AS_OF,
                new BigDecimal("10000.00"),
                "CAD",
                workedExample(requestId).outstandingItems(),
                WHY,
                requestId);
        assertThatThrownBy(() -> service.establish(BANK, otherCurrency))
                .isInstanceOfSatisfying(
                        CashSetupException.class,
                        e -> assertThat(e.getCode()).isEqualTo(CashSetupException.Code.IDEMPOTENCY_CONFLICT));
        assertThatThrownBy(() -> service.establish(BANK, other))
                .isInstanceOfSatisfying(
                        CashSetupException.class,
                        e -> assertThat(e.getCode()).isEqualTo(CashSetupException.Code.IDEMPOTENCY_CONFLICT));
    }

    @Test
    @DisplayName("a duplicate requestId that waited on the account's lock answers with the first result, not a 409")
    void duplicateThatWaitedOnTheLockIsAReplay() {
        UUID requestId = UUID.randomUUID();
        service.establish(BANK, workedExample(requestId));
        BankOpeningBalance row = saved.get(0);
        // Not found before the lock (the first had not committed), found after it.
        when(openings.findByRequestId(requestId)).thenReturn(Optional.empty(), Optional.of(row));

        assertThat(service.establish(BANK, workedExample(requestId)).replayed()).isTrue();
        assertThat(entries).hasSize(1);
    }

    // ---- helpers --------------------------------------------------------------------------------------------

    private void assertRefused(CashSetupException.Code code) {
        assertThatThrownBy(() -> service.establish(BANK, workedExample(UUID.randomUUID())))
                .isInstanceOfSatisfying(
                        CashSetupException.class, e -> assertThat(e.getCode()).isEqualTo(code));
        verify(journalEntries, never()).createJournalEntry(any());
        assertThat(saved).isEmpty();
    }

    private JournalEntryCreateRequest onlyEntry() {
        ArgumentCaptor<JournalEntryCreateRequest> captor = ArgumentCaptor.forClass(JournalEntryCreateRequest.class);
        verify(journalEntries).createJournalEntry(captor.capture());
        return captor.getValue();
    }

    private static BankOpeningBalanceRequest workedExample(UUID requestId) {
        return request(
                "10000.00",
                List.of(
                        new BankOpeningBalanceRequest.OutstandingItem(
                                BankOpeningItemType.OUTSTANDING_CHECK,
                                "1043",
                                LocalDate.of(2025, 10, 28),
                                new BigDecimal("450.00")),
                        new BankOpeningBalanceRequest.OutstandingItem(
                                BankOpeningItemType.DEPOSIT_IN_TRANSIT,
                                " DEP-1031 ",
                                AS_OF,
                                new BigDecimal("1200.00"))),
                requestId);
    }

    private static BankOpeningBalanceRequest.OutstandingItem item(
            BankOpeningItemType type, String reference, String amount) {
        return new BankOpeningBalanceRequest.OutstandingItem(type, reference, AS_OF, new BigDecimal(amount));
    }

    private static BankOpeningBalanceRequest request(
            String statement, List<BankOpeningBalanceRequest.OutstandingItem> items, UUID requestId) {
        return new BankOpeningBalanceRequest(AS_OF, new BigDecimal(statement), "USD", items, WHY, requestId);
    }
}
