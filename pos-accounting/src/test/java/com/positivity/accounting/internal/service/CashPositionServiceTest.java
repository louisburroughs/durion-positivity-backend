package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.bankrec.readmodel.BankPositionFacts;
import com.positivity.accounting.internal.bankrec.readmodel.BankPositionFacts.Facts;
import com.positivity.accounting.internal.bankrec.readmodel.BankPositionFacts.ItemTotal;
import com.positivity.accounting.internal.config.LedgerCurrency;
import com.positivity.accounting.internal.dto.CashOutlookResponse;
import com.positivity.accounting.internal.dto.CashOutlookResponse.BillsLane;
import com.positivity.accounting.internal.dto.CashOutlookResponse.Figure;
import com.positivity.accounting.internal.dto.CashOutlookResponse.Point;
import com.positivity.accounting.internal.dto.CashPositionResponse;
import com.positivity.accounting.internal.dto.CashPositionResponse.AccountRow;
import com.positivity.accounting.internal.dto.CashPositionResponse.NeedsAttention;
import com.positivity.accounting.internal.dto.UnpaidWalkInSalesResponse;
import com.positivity.accounting.internal.dto.WalkInOpenInvoice;
import com.positivity.accounting.internal.entity.APPayment;
import com.positivity.accounting.internal.entity.AccountingEvent;
import com.positivity.accounting.internal.entity.GLAccount;
import com.positivity.accounting.internal.entity.UndepositedSession;
import com.positivity.accounting.internal.enums.APPaymentStatus;
import com.positivity.accounting.internal.enums.AccountSubtype;
import com.positivity.accounting.internal.enums.CashAttentionCode;
import com.positivity.accounting.internal.enums.CashAttentionReason;
import com.positivity.accounting.internal.enums.PaymentMethod;
import com.positivity.accounting.internal.enums.UndepositedSessionStatus;
import com.positivity.accounting.internal.repository.APPaymentRepository;
import com.positivity.accounting.internal.repository.AccountingEventRepository;
import com.positivity.accounting.internal.repository.GLAccountRepository;
import com.positivity.accounting.internal.repository.JournalEntryLineRepository;
import com.positivity.accounting.internal.repository.UndepositedSessionRepository;
import com.positivity.accounting.internal.security.AccountingPermissions;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * The cash position of #2515 (CAP:550 S19; SPEC-accounting-workspace §4.1; AW9): the account-role rule and its
 * balances, the statement gate, foreign-currency exclusion and every needs-attention code. The cash accounts and the
 * attention sources are the real classes over mocked repositories, so the role rule is exercised end to end.
 */
@DisplayName("CashPositionService (#2515)")
class CashPositionServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-17T15:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 17);

    private static final UUID BANK = UUID.fromString("019a0000-0000-7000-8000-000000001000");
    private static final UUID EURO_BANK = UUID.fromString("019a0000-0000-7000-8000-000000001010");
    private static final UUID UNDEPOSITED = UUID.fromString("019a0000-0000-7000-8000-000000001090");
    private static final UUID CLEARING = UUID.fromString("019a0000-0000-7000-8000-000000001095");
    private static final UUID DRAWERS = UUID.fromString("019a0000-0000-7000-8000-000000001080");
    private static final UUID SUSPENSE = UUID.fromString("019a0000-0000-7000-8000-000000002350");

    private final BankPositionFacts bankPositionFacts = mock(BankPositionFacts.class);
    private final GLAccountRepository glAccounts = mock(GLAccountRepository.class);
    private final JournalEntryLineRepository journalEntryLines = mock(JournalEntryLineRepository.class);
    private final GLMappingResolver glMappingResolver = mock(GLMappingResolver.class);
    private final AccountingCalendarZoneResolver zoneResolver = mock(AccountingCalendarZoneResolver.class);
    private final UndepositedSessionRepository sessions = mock(UndepositedSessionRepository.class);
    private final APPaymentRepository apPayments = mock(APPaymentRepository.class);
    private final CashOutlookService outlookService = mock(CashOutlookService.class);
    private final UnpaidWalkInSalesService walkInSales = mock(UnpaidWalkInSalesService.class);
    private final AccountingEventRepository accountingEvents = mock(AccountingEventRepository.class);
    private final CashAndPayablesSettings settings = mock(CashAndPayablesSettings.class);
    private final LedgerCurrency ledgerCurrency = new LedgerCurrency("USD");
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private final Map<UUID, String> balances = new HashMap<>();
    private CashPositionServiceImpl service;

    @BeforeEach
    void setUp() {
        when(zoneResolver.today()).thenReturn(TODAY);
        when(zoneResolver.postingDateTime(any(Instant.class)))
                .thenAnswer(invocation -> LocalDateTime.ofInstant(invocation.getArgument(0), ZoneOffset.UTC));
        when(zoneResolver.postingDate(any(Instant.class)))
                .thenAnswer(invocation -> LocalDate.ofInstant(invocation.getArgument(0), ZoneOffset.UTC));

        when(glAccounts.findBySubtypeActiveAt(eq(AccountSubtype.BANK_CASH), any(), any()))
                .thenReturn(List.of(bankAccount(BANK, "1000", true), bankAccount(EURO_BANK, "1010", true)));
        when(bankPositionFacts.currenciesOf(anyCollection())).thenReturn(Map.of(BANK, "USD", EURO_BANK, "EUR"));
        when(glMappingResolver.findGLAccount(
                        eq(DepositServiceImpl.POSTING_CATEGORY), eq(DepositServiceImpl.UNDEPOSITED_FUNDS_KEY), any()))
                .thenReturn(Optional.of(UNDEPOSITED));
        when(glMappingResolver.findGLAccount(
                        eq(DepositServiceImpl.POSTING_CATEGORY), eq(DepositServiceImpl.CASH_CLEARING_KEY), any()))
                .thenReturn(Optional.of(CLEARING));
        when(glMappingResolver.findGLAccount(
                        eq(SettlementReconciliationServiceImpl.SETTLEMENT_CATEGORY),
                        eq(SettlementReconciliationServiceImpl.SUSPENSE_MAPPING_KEY),
                        any()))
                .thenReturn(Optional.of(SUSPENSE));
        when(glAccounts.findAllById(any()))
                .thenReturn(List.of(account(UNDEPOSITED, "1090"), account(CLEARING, "1095")));
        when(glAccounts.findBySubtypeActiveAt(eq(AccountSubtype.CASH_ON_HAND), any(), any()))
                .thenReturn(List.of(account(DRAWERS, "1080")));
        when(journalEntryLines.getAccountBalances(anyCollection(), any())).thenAnswer(invocation -> {
            List<JournalEntryLineRepository.AccountBalance> rows = new ArrayList<>();
            for (Map.Entry<UUID, String> entry : balances.entrySet()) {
                rows.add(balance(entry.getKey(), entry.getValue()));
            }
            return rows;
        });
        balances.put(BANK, "12400.00");
        balances.put(UNDEPOSITED, "1240.00");
        balances.put(CLEARING, "-43.00");
        balances.put(DRAWERS, "600.00");

        when(settings.settings()).thenReturn(new CashAndPayablesSettings.Settings("NET30", null, 3, 14, 30));
        when(sessions.findByStatusOrderByClosedAtAscSessionIdAsc(UndepositedSessionStatus.UNDEPOSITED))
                .thenReturn(List.of());
        when(apPayments.findUnappliedByMethodBefore(any(), any(), any(), any())).thenReturn(List.of());
        when(walkInSales.read()).thenReturn(walkIn(true, "0.00", 0));
        when(accountingEvents.findFirstByEventTypeInOrderByReceivedAtDesc(any()))
                .thenReturn(Optional.empty());
        when(zoneResolver.zone()).thenReturn(ZoneOffset.UTC);
        when(outlookService.outlook(eq(30), any())).thenReturn(outlook(false));

        CashAccounts cash = new CashAccounts(
                bankPositionFacts,
                glAccounts,
                journalEntryLines,
                glMappingResolver,
                ledgerCurrency,
                zoneResolver,
                clock);
        CashAttentionSources sources = new CashAttentionSources(sessions, apPayments, zoneResolver, ledgerCurrency);
        service = new CashPositionServiceImpl(
                cash,
                bankPositionFacts,
                sources,
                outlookService,
                walkInSales,
                accountingEvents,
                settings,
                zoneResolver,
                ledgerCurrency,
                clock,
                Duration.ofMinutes(15));
    }

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    // ---- components ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("AC 1: 12,400 in the bank, 1,240 and -43 waiting, 600 in drawers make 14,197.00")
    void components() {
        CashPositionResponse position = service.read();

        assertThat(position.total()).isEqualByComparingTo("14197.00");
        assertThat(position.inTheBank().total()).isEqualByComparingTo("12400.00");
        assertThat(position.waitingToBeDeposited().total()).isEqualByComparingTo("1197.00");
        assertThat(position.keptInDrawers().total()).isEqualByComparingTo("600.00");
        assertThat(position.waitingToBeDeposited().accounts())
                .extracting(AccountRow::accountCode, row -> row.balance().toPlainString())
                .containsExactly(tuple("1090", "1240.00"), tuple("1095", "-43.00"));
        assertThat(position.asOf()).isEqualTo(NOW);
        assertThat(position.currencyCode()).isEqualTo("USD");
    }

    @Test
    @DisplayName("AC 2: settlement suspense is a needs-attention item and in no component")
    void suspenseIsNeverCash() {
        balances.put(SUSPENSE, "-35.00");

        CashPositionResponse position = service.read();

        assertThat(position.total()).isEqualByComparingTo("14197.00");
        assertThat(position.inTheBank().accounts())
                .extracting(AccountRow::glAccountId)
                .doesNotContain(SUSPENSE);
        assertThat(position.waitingToBeDeposited().accounts())
                .extracting(AccountRow::glAccountId)
                .doesNotContain(SUSPENSE);
        assertThat(position.keptInDrawers().accounts())
                .extracting(AccountRow::glAccountId)
                .doesNotContain(SUSPENSE);
        assertThat(attention(position, CashAttentionCode.SETTLEMENT_SUSPENSE_NOT_ZERO))
                .singleElement()
                .satisfies(item -> assertThat(item.amount()).isEqualByComparingTo("35.00"));
    }

    @Test
    @DisplayName("a zero suspense balance raises nothing")
    void zeroSuspenseRaisesNothing() {
        balances.put(SUSPENSE, "0.00");

        assertThat(attention(service.read(), CashAttentionCode.SETTLEMENT_SUSPENSE_NOT_ZERO))
                .isEmpty();
    }

    @Test
    @DisplayName("with no accounts in a role the component is zero with no rows")
    void emptyRoles() {
        when(glAccounts.findBySubtypeActiveAt(eq(AccountSubtype.BANK_CASH), any(), any()))
                .thenReturn(List.of());
        when(glAccounts.findAllById(any())).thenReturn(List.of());
        when(glAccounts.findBySubtypeActiveAt(eq(AccountSubtype.CASH_ON_HAND), any(), any()))
                .thenReturn(List.of());
        balances.clear();

        CashPositionResponse position = service.read();

        assertThat(position.total()).isEqualByComparingTo("0.00");
        assertThat(position.inTheBank().accounts()).isEmpty();
        assertThat(position.waitingToBeDeposited().accounts()).isEmpty();
        assertThat(position.keptInDrawers().accounts()).isEmpty();
        assertThat(position.needsAttention()).isEmpty();
    }

    @Test
    @DisplayName("a foreign-currency bank account is left out of the rows and the total, and counted")
    void foreignCurrencyBankAccountIsExcluded() {
        balances.put(EURO_BANK, "9999.00");

        CashPositionResponse position = service.read();

        assertThat(position.excludedForeignCurrencyAccounts()).isEqualTo(1);
        assertThat(position.inTheBank().accounts())
                .extracting(AccountRow::accountCode)
                .containsExactly("1000");
        assertThat(position.total()).isEqualByComparingTo("14197.00");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<UUID>> asked = ArgumentCaptor.forClass(Collection.class);
        verify(journalEntryLines).getAccountBalances(asked.capture(), any());
        assertThat(asked.getValue()).contains(BANK).doesNotContain(EURO_BANK);
    }

    @Test
    @DisplayName("a non-reconcilable BANK_CASH account's balance is in the bank and the total, without statement facts")
    void nonReconcilableBankAccountIsInTheTotal() {
        UUID petty = UUID.fromString("019a0000-0000-7000-8000-000000001020");
        when(glAccounts.findBySubtypeActiveAt(eq(AccountSubtype.BANK_CASH), any(), any()))
                .thenReturn(List.of(bankAccount(BANK, "1000", true), bankAccount(petty, "1020", false)));
        when(bankPositionFacts.currenciesOf(anyCollection())).thenReturn(Map.of());
        balances.put(petty, "300.00");
        signIn(AccountingPermissions.REPORTING_VIEW_FINANCIAL_STATEMENTS, AccountingPermissions.RECONCILIATION_VIEW);
        when(bankPositionFacts.factsOf(anyCollection()))
                .thenReturn(Map.of(
                        BANK,
                        new Facts(
                                new BigDecimal("12100.00"),
                                LocalDate.of(2026, 9, 30),
                                null,
                                new ItemTotal(0, BigDecimal.ZERO),
                                new ItemTotal(0, BigDecimal.ZERO),
                                0)));

        CashPositionResponse position = service.read();

        assertThat(position.inTheBank().total()).isEqualByComparingTo("12700.00");
        assertThat(position.total()).isEqualByComparingTo("14497.00");
        assertThat(position.inTheBank().accounts())
                .extracting(AccountRow::accountCode)
                .containsExactly("1000", "1020");
        assertThat(position.inTheBank().accounts().get(0).statementClosingBalance())
                .isEqualByComparingTo("12100.00");
        assertThat(position.inTheBank().accounts().get(1).statementClosingBalance())
                .isNull();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<UUID>> asked = ArgumentCaptor.forClass(Collection.class);
        verify(bankPositionFacts).factsOf(asked.capture());
        assertThat(asked.getValue()).containsExactly(BANK);
    }

    @Test
    @DisplayName(
            "balances count entries dated before the start of tomorrow on the accounting date, not post-dated ones")
    void balancesAreBoundedByTheAccountingDate() {
        service.read();

        verify(journalEntryLines).getAccountBalances(anyCollection(), eq(LocalDateTime.of(2026, 10, 18, 0, 0)));
    }

    @Test
    @DisplayName("one cash-position read projects the outlook once")
    void computesTheOutlookOnce() {
        service.read();

        verify(outlookService, times(1)).outlook(eq(30), any());
        verify(outlookService, never()).outlook(30);
    }

    // ---- statement fields ------------------------------------------------------------------------------------

    @Test
    @DisplayName("AC 3: without accounting:reconciliation:view no statement field is returned")
    void noStatementFieldsWithoutReconciliationView() {
        signIn(AccountingPermissions.REPORTING_VIEW_FINANCIAL_STATEMENTS);

        AccountRow bank = service.read().inTheBank().accounts().getFirst();

        assertThat(bank.statementClosingBalance()).isNull();
        assertThat(bank.statementEndDate()).isNull();
        assertThat(bank.reconciledThrough()).isNull();
        assertThat(bank.depositsInTransit()).isNull();
        assertThat(bank.outstandingChecks()).isNull();
        assertThat(bank.unmatchedBankLineCount()).isNull();
        verify(bankPositionFacts, never()).factsOf(anyCollection());
    }

    @Test
    @DisplayName(
            "with accounting:reconciliation:view each bank row adds the statement facts, never blended into the total")
    void statementFieldsWithReconciliationView() {
        signIn(AccountingPermissions.REPORTING_VIEW_FINANCIAL_STATEMENTS, AccountingPermissions.RECONCILIATION_VIEW);
        when(bankPositionFacts.factsOf(anyCollection()))
                .thenReturn(Map.of(
                        BANK,
                        new Facts(
                                new BigDecimal("12100.0000"),
                                LocalDate.of(2026, 9, 30),
                                LocalDate.of(2026, 9, 30),
                                new ItemTotal(1, new BigDecimal("450.0000")),
                                new ItemTotal(2, new BigDecimal("-250.0000")),
                                3)));

        CashPositionResponse position = service.read();
        AccountRow bank = position.inTheBank().accounts().getFirst();

        assertThat(bank.statementClosingBalance()).isEqualByComparingTo("12100.00");
        assertThat(bank.statementEndDate()).isEqualTo(LocalDate.of(2026, 9, 30));
        assertThat(bank.reconciledThrough()).isEqualTo(LocalDate.of(2026, 9, 30));
        assertThat(bank.depositsInTransit().count()).isEqualTo(1);
        assertThat(bank.depositsInTransit().amount()).isEqualByComparingTo("450.00");
        assertThat(bank.outstandingChecks().count()).isEqualTo(2);
        assertThat(bank.outstandingChecks().amount()).isEqualByComparingTo("-250.00");
        assertThat(bank.unmatchedBankLineCount()).isEqualTo(3L);
        assertThat(bank.balance()).isEqualByComparingTo("12400.00");
        assertThat(position.total()).isEqualByComparingTo("14197.00");
    }

    // ---- needs attention -------------------------------------------------------------------------------------

    @Test
    @DisplayName("an undeposited session older than the threshold is aged; one exactly at it is not")
    void agedUndepositedSessions() {
        when(sessions.findByStatusOrderByClosedAtAscSessionIdAsc(UndepositedSessionStatus.UNDEPOSITED))
                .thenReturn(List.of(
                        session(LocalDate.of(2026, 10, 12), "100.00", "100.00", "0.00"),
                        session(LocalDate.of(2026, 10, 13), "100.00", "100.00", "0.00"),
                        session(LocalDate.of(2026, 10, 14), "100.00", "100.00", "0.00")));

        List<NeedsAttention> items = attention(service.read(), CashAttentionCode.UNDEPOSITED_CASH_AGED);

        assertThat(items).singleElement().satisfies(item -> {
            assertThat(item.reason()).isNull();
            assertThat(item.count()).isEqualTo(2);
            assertThat(item.amount()).isEqualByComparingTo("200.00");
            assertThat(item.since()).isEqualTo(LocalDate.of(2026, 10, 12));
        });
    }

    @Test
    @DisplayName("an aged session that does not balance is its own item with reason UNBALANCED")
    void agedUnbalancedSession() {
        when(sessions.findByStatusOrderByClosedAtAscSessionIdAsc(UndepositedSessionStatus.UNDEPOSITED))
                .thenReturn(List.of(
                        session(LocalDate.of(2026, 10, 11), "100.00", "100.00", "0.00"),
                        session(LocalDate.of(2026, 10, 12), "90.00", "100.00", "0.00")));

        List<NeedsAttention> items = attention(service.read(), CashAttentionCode.UNDEPOSITED_CASH_AGED);

        assertThat(items).hasSize(2);
        assertThat(items.get(0).reason()).isNull();
        assertThat(items.get(0).amount()).isEqualByComparingTo("100.00");
        assertThat(items.get(0).since()).isEqualTo(LocalDate.of(2026, 10, 11));
        assertThat(items.get(1).reason()).isEqualTo(CashAttentionReason.UNBALANCED);
        assertThat(items.get(1).amount()).isEqualByComparingTo("90.00");
        assertThat(items.get(1).count()).isEqualTo(1);
        assertThat(items.get(1).since()).isEqualTo(LocalDate.of(2026, 10, 12));
    }

    @Test
    @DisplayName("a session whose clearing net explains the difference balances")
    void clearingNetBalancesTheSession() {
        // Drops 55 = expected cash 100 + clearing net -45 (a petty expense paid from the drawer).
        when(sessions.findByStatusOrderByClosedAtAscSessionIdAsc(UndepositedSessionStatus.UNDEPOSITED))
                .thenReturn(List.of(session(LocalDate.of(2026, 10, 1), "55.00", "100.00", "-45.00")));

        List<NeedsAttention> items = attention(service.read(), CashAttentionCode.UNDEPOSITED_CASH_AGED);

        assertThat(items)
                .singleElement()
                .satisfies(item -> assertThat(item.reason()).isNull());
    }

    @Test
    @DisplayName("the clearing residual is flagged when 1095 is not zero and no session waits")
    void clearingResidualWithoutSessions() {
        List<NeedsAttention> items = attention(service.read(), CashAttentionCode.CLEARING_RESIDUAL_NOT_ZERO);

        assertThat(items).singleElement().satisfies(item -> {
            assertThat(item.amount()).isEqualByComparingTo("-43.00");
            assertThat(item.count()).isNull();
            assertThat(item.since()).isNull();
        });
    }

    @Test
    @DisplayName("the clearing residual is not flagged while an undeposited session waits")
    void clearingResidualWithSessionsWaiting() {
        when(sessions.findByStatusOrderByClosedAtAscSessionIdAsc(UndepositedSessionStatus.UNDEPOSITED))
                .thenReturn(List.of(session(LocalDate.of(2026, 10, 16), "100.00", "100.00", "0.00")));

        assertThat(attention(service.read(), CashAttentionCode.CLEARING_RESIDUAL_NOT_ZERO))
                .isEmpty();
    }

    @Test
    @DisplayName("a zero clearing balance is not a residual")
    void zeroClearingIsNotAResidual() {
        balances.put(CLEARING, "0.00");

        assertThat(attention(service.read(), CashAttentionCode.CLEARING_RESIDUAL_NOT_ZERO))
                .isEmpty();
    }

    @Test
    @DisplayName("unapplied vendor cash-on-delivery money older than the threshold is aged")
    void agedVendorCod() {
        when(apPayments.findUnappliedByMethodBefore(any(), any(), any(), any()))
                .thenReturn(List.of(
                        codPayment(LocalDate.of(2026, 9, 20), "145.00"),
                        codPayment(LocalDate.of(2026, 10, 1), "55.00")));

        List<NeedsAttention> items = attention(service.read(), CashAttentionCode.VENDOR_COD_UNAPPLIED_AGED);

        assertThat(items).singleElement().satisfies(item -> {
            assertThat(item.amount()).isEqualByComparingTo("200.00");
            assertThat(item.count()).isEqualTo(2);
            assertThat(item.since()).isEqualTo(LocalDate.of(2026, 9, 20));
        });
        // 14 days on 2026-10-17: payments dated before 2026-10-03, cash only, not voided or returned, in USD.
        verify(apPayments)
                .findUnappliedByMethodBefore(
                        PaymentMethod.CASH,
                        "USD",
                        Set.of(APPaymentStatus.VOIDED, APPaymentStatus.RETURNED),
                        LocalDate.of(2026, 10, 3));
    }

    @Test
    @DisplayName("BELOW_SAFETY_CUSHION carries the outlook's low point and its day")
    void belowCushion() {
        when(outlookService.outlook(eq(30), any())).thenReturn(outlook(true));

        List<NeedsAttention> items = attention(service.read(), CashAttentionCode.BELOW_SAFETY_CUSHION);

        assertThat(items).singleElement().satisfies(item -> {
            assertThat(item.amount()).isEqualByComparingTo("4200.00");
            assertThat(item.since()).isEqualTo(LocalDate.of(2026, 10, 24));
        });
    }

    @Test
    @DisplayName("no cushion breach, no BELOW_SAFETY_CUSHION")
    void noCushionBreach() {
        assertThat(attention(service.read(), CashAttentionCode.BELOW_SAFETY_CUSHION))
                .isEmpty();
    }

    @Test
    @DisplayName("the codes come in a fixed order")
    void fixedOrder() {
        balances.put(SUSPENSE, "-35.00");
        when(sessions.findByStatusOrderByClosedAtAscSessionIdAsc(UndepositedSessionStatus.UNDEPOSITED))
                .thenReturn(List.of(session(LocalDate.of(2026, 10, 1), "100.00", "100.00", "0.00")));
        when(apPayments.findUnappliedByMethodBefore(any(), any(), any(), any()))
                .thenReturn(List.of(codPayment(LocalDate.of(2026, 9, 20), "145.00")));
        when(outlookService.outlook(eq(30), any())).thenReturn(outlook(true));

        assertThat(service.read().needsAttention())
                .extracting(NeedsAttention::code)
                .containsExactly(
                        CashAttentionCode.SETTLEMENT_SUSPENSE_NOT_ZERO,
                        CashAttentionCode.UNDEPOSITED_CASH_AGED,
                        CashAttentionCode.VENDOR_COD_UNAPPLIED_AGED,
                        CashAttentionCode.BELOW_SAFETY_CUSHION);
    }

    // ---- unpaid walk-in sales and lag ------------------------------------------------------------------------

    @Test
    @DisplayName("AC 2: a CASH balance of 86.00 shows as unpaid walk-in sales")
    void unpaidWalkInSales() {
        when(walkInSales.read()).thenReturn(walkIn(true, "86.00", 2));

        assertThat(service.read().unpaidWalkInSales()).satisfies(sales -> {
            assertThat(sales.balance()).isEqualByComparingTo("86.00");
            assertThat(sales.invoiceCount()).isEqualTo(2);
            assertThat(sales.houseAccountKnown()).isTrue();
        });
    }

    @Test
    @DisplayName("a zero walk-in balance is left out, but an unknown CASH account is passed through")
    void unpaidWalkInSalesOmittedWhenZeroAndKnown() {
        assertThat(service.read().unpaidWalkInSales()).isNull();

        when(walkInSales.read()).thenReturn(walkIn(false, "0.00", 0));
        assertThat(service.read().unpaidWalkInSales()).satisfies(sales -> {
            assertThat(sales.houseAccountKnown()).isFalse();
            assertThat(sales.balance()).isEqualByComparingTo("0.00");
        });
    }

    @Test
    @DisplayName("a fact received after the lag threshold marks the position lagging; eventFedAsOf is its receivedAt")
    void laggingWhenTheNewestFactArrivedLate() {
        Instant received = NOW.minus(Duration.ofMinutes(1));
        when(accountingEvents.findFirstByEventTypeInOrderByReceivedAtDesc(any()))
                .thenReturn(Optional.of(fact(received, received.minus(Duration.ofMinutes(20)))));

        CashPositionResponse position = service.read();

        assertThat(position.eventFedAsOf()).isEqualTo(received);
        assertThat(position.lagging()).isTrue();
    }

    @Test
    @DisplayName("a fact received within the threshold is not lagging, however old; at the threshold exactly it is not")
    void notLaggingWhenTheNewestFactArrivedPromptly() {
        Instant received = NOW.minus(Duration.ofDays(3));
        when(accountingEvents.findFirstByEventTypeInOrderByReceivedAtDesc(any()))
                .thenReturn(Optional.of(fact(received, received.minus(Duration.ofMinutes(5)))));
        assertThat(service.read().lagging())
                .as("a quiet shop is not a lagging replica")
                .isFalse();

        when(accountingEvents.findFirstByEventTypeInOrderByReceivedAtDesc(any()))
                .thenReturn(Optional.of(fact(received, received.minus(Duration.ofMinutes(15)))));
        CashPositionResponse atThreshold = service.read();
        assertThat(atThreshold.eventFedAsOf()).isEqualTo(received);
        assertThat(atThreshold.lagging()).isFalse();
    }

    @Test
    @DisplayName("with no invoice or session fact ingested, eventFedAsOf is null and nothing lags")
    void noIngestedFact() {
        CashPositionResponse none = service.read();

        assertThat(none.eventFedAsOf()).isNull();
        assertThat(none.lagging()).isFalse();
        verify(accountingEvents)
                .findFirstByEventTypeInOrderByReceivedAtDesc(
                        List.of("invoice.invoice.updated", "order.session.closed"));
    }

    // ---- helpers ---------------------------------------------------------------------------------------------

    private static List<NeedsAttention> attention(CashPositionResponse position, CashAttentionCode code) {
        return position.needsAttention().stream()
                .filter(item -> item.code() == code)
                .toList();
    }

    private static void signIn(String... authorities) {
        UsernamePasswordAuthenticationToken caller = new UsernamePasswordAuthenticationToken(
                "clerk.ann",
                "n/a",
                Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList());
        SecurityContextHolder.getContext().setAuthentication(caller);
    }

    private static AccountingEvent fact(Instant receivedAt, Instant occurredAt) {
        AccountingEvent event = mock(AccountingEvent.class);
        when(event.getReceivedAt()).thenReturn(receivedAt);
        when(event.getTransactionDate()).thenReturn(LocalDateTime.ofInstant(occurredAt, ZoneOffset.UTC));
        return event;
    }

    private static GLAccount bankAccount(UUID id, String code, boolean reconcilable) {
        GLAccount account = account(id, code);
        account.setAccountSubtype(AccountSubtype.BANK_CASH);
        account.setReconcilable(reconcilable);
        return account;
    }

    private static GLAccount account(UUID id, String code) {
        GLAccount account = new GLAccount(id);
        account.setAccountCode(code);
        account.setAccountName("Account " + code);
        return account;
    }

    private static JournalEntryLineRepository.AccountBalance balance(UUID id, String amount) {
        return new JournalEntryLineRepository.AccountBalance() {
            @Override
            public UUID getGlAccountId() {
                return id;
            }

            @Override
            public BigDecimal getBalance() {
                return new BigDecimal(amount);
            }
        };
    }

    private static UndepositedSession session(LocalDate closed, String drops, String expected, String clearingNet) {
        UndepositedSession session = new UndepositedSession();
        session.setSessionId(UUID.randomUUID());
        session.setClosedAt(closed.atTime(12, 0).toInstant(ZoneOffset.UTC));
        session.setDepositAmount(new BigDecimal(drops));
        session.setExpectedCash(new BigDecimal(expected));
        session.setClearingNet(new BigDecimal(clearingNet));
        session.setStatus(UndepositedSessionStatus.UNDEPOSITED);
        return session;
    }

    private static APPayment codPayment(LocalDate date, String unapplied) {
        APPayment payment = new APPayment(UUID.randomUUID());
        payment.setPaymentMethod(PaymentMethod.CASH);
        payment.setPaymentDate(date);
        payment.setUnappliedAmount(new BigDecimal(unapplied));
        payment.setCurrency("USD");
        payment.setStatus(APPaymentStatus.GL_POSTED);
        return payment;
    }

    private static UnpaidWalkInSalesResponse walkIn(boolean known, String balance, int invoices) {
        List<WalkInOpenInvoice> open = new ArrayList<>();
        for (int i = 0; i < invoices; i++) {
            open.add(WalkInOpenInvoice.builder().invoiceNumber("INV-" + i).build());
        }
        return UnpaidWalkInSalesResponse.builder()
                .asOf(NOW)
                .houseAccountKnown(known)
                .currencyCode("USD")
                .balance(new BigDecimal(balance))
                .openInvoices(open)
                .build();
    }

    private static CashOutlookResponse outlook(boolean below) {
        Figure none = new Figure(new BigDecimal("0.00"), 0);
        Point low = new Point(
                LocalDate.of(2026, 10, 24),
                new BigDecimal("4200.00"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO);
        return new CashOutlookResponse(
                NOW,
                true,
                "USD",
                TODAY,
                30,
                new BigDecimal("13597.00"),
                List.of(low),
                low,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                none,
                none,
                none,
                none,
                new BillsLane(none, none, none, none, BigDecimal.ZERO),
                below ? new BigDecimal("5000.00") : null,
                below);
    }
}
