package com.positivity.accounting.internal.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.accounting.BaseIntegrationTest;
import com.positivity.accounting.internal.bankrec.entity.BankAccountProfile;
import com.positivity.accounting.internal.bankrec.entity.BankReconciliationOutstandingItem;
import com.positivity.accounting.internal.bankrec.entity.BankStatement;
import com.positivity.accounting.internal.bankrec.enums.BankStatementStatus;
import com.positivity.accounting.internal.bankrec.enums.OutstandingItemKind;
import com.positivity.accounting.internal.bankrec.enums.OutstandingItemSide;
import com.positivity.accounting.internal.bankrec.enums.OutstandingItemStatus;
import com.positivity.accounting.internal.bankrec.enums.SourceKind;
import com.positivity.accounting.internal.bankrec.repository.AccountKindTotals;
import com.positivity.accounting.internal.bankrec.repository.BankAccountProfileRepository;
import com.positivity.accounting.internal.bankrec.repository.BankReconciliationOutstandingItemRepository;
import com.positivity.accounting.internal.bankrec.repository.BankStatementRepository;
import com.positivity.accounting.internal.entity.APPayment;
import com.positivity.accounting.internal.entity.ExtInvoice;
import com.positivity.accounting.internal.entity.GLAccount;
import com.positivity.accounting.internal.entity.JournalEntry;
import com.positivity.accounting.internal.entity.JournalEntryLine;
import com.positivity.accounting.internal.enums.APPaymentStatus;
import com.positivity.accounting.internal.enums.AccountSubtype;
import com.positivity.accounting.internal.enums.AccountType;
import com.positivity.accounting.internal.enums.JournalEntryStatus;
import com.positivity.accounting.internal.enums.PaymentMethod;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * The JPQL behind the cash position (#2515) executed once on the H2 test profile: the balances of many accounts in one
 * query (POSTED and REVERSED only, bounded by date), the latest committed statement, the open outstanding items by kind
 * since the baseline, the unapplied vendor cash payments and the newest CASH-party invoice write.
 */
@Transactional
@DisplayName("Cash position queries (#2515)")
class CashQueriesTest extends BaseIntegrationTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 17);

    @Autowired
    private GLAccountRepository glAccounts;

    @Autowired
    private JournalEntryRepository journalEntries;

    @Autowired
    private JournalEntryLineRepository journalEntryLines;

    @Autowired
    private BankStatementRepository statements;

    @Autowired
    private BankReconciliationOutstandingItemRepository outstandingItems;

    @Autowired
    private BankAccountProfileRepository profiles;

    @Autowired
    private APPaymentRepository apPayments;

    @Autowired
    private ExtInvoiceRepository extInvoices;

    private GLAccount account(String code, AccountType type, AccountSubtype subtype) {
        GLAccount account = new GLAccount(UUID.randomUUID());
        account.setAccountCode(code);
        account.setAccountName("Test " + code);
        account.setAccountType(type);
        account.setAccountSubtype(subtype);
        account.setActivationDate(LocalDateTime.of(2020, 1, 1, 0, 0));
        account.setCreatedBy("cash-query-test");
        account.setModifiedBy("cash-query-test");
        return glAccounts.saveAndFlush(account);
    }

    private void entry(
            String number,
            LocalDate date,
            JournalEntryStatus status,
            GLAccount debit,
            GLAccount credit,
            String amount) {
        BigDecimal value = new BigDecimal(amount);
        JournalEntry entry = new JournalEntry();
        entry.setStatus(status);
        entry.setEntryNumber(number);
        entry.setTransactionDate(date.atTime(12, 0));
        entry.setSourceEventType("CASH_QUERY_TEST");
        entry.setCreatedBy("cash-query-test");
        entry.setModifiedBy("cash-query-test");
        entry.setTotalDebits(value);
        entry.setTotalCredits(value);
        entry.setIsBalanced(true);
        entry.addLine(line(entry, 1, debit, value, BigDecimal.ZERO));
        entry.addLine(line(entry, 2, credit, BigDecimal.ZERO, value));
        journalEntries.saveAndFlush(entry);
    }

    private static JournalEntryLine line(
            JournalEntry entry, int number, GLAccount account, BigDecimal debit, BigDecimal credit) {
        JournalEntryLine line = new JournalEntryLine();
        line.setJournalEntry(entry);
        line.setLineNumber(number);
        line.setGlAccount(account);
        line.setAccountCode(account.getAccountCode());
        line.setAccountName(account.getAccountName());
        line.setDebitAmount(debit);
        line.setCreditAmount(credit);
        line.setDescription("cash query test");
        return line;
    }

    @Test
    @DisplayName(
            "getAccountBalances sums POSTED and REVERSED lines per account before the bound, not DRAFT or later ones")
    void accountBalances() {
        GLAccount bank = account("CQ-1", AccountType.ASSET, AccountSubtype.BANK_CASH);
        GLAccount other = account("CQ-2", AccountType.LIABILITY, AccountSubtype.CURRENT_LIABILITY);
        GLAccount untouched = account("CQ-3", AccountType.ASSET, AccountSubtype.BANK_CASH);
        entry("CQ-JE-1", TODAY.minusDays(1), JournalEntryStatus.POSTED, bank, other, "100.00");
        entry("CQ-JE-2", TODAY, JournalEntryStatus.POSTED, other, bank, "40.00");
        entry("CQ-JE-3", TODAY.minusDays(2), JournalEntryStatus.REVERSED, bank, other, "10.00");
        entry("CQ-JE-4", TODAY, JournalEntryStatus.DRAFT, bank, other, "999.00");
        entry("CQ-JE-5", TODAY.plusDays(1), JournalEntryStatus.POSTED, bank, other, "7.00");

        Map<UUID, BigDecimal> balances = journalEntryLines
                .getAccountBalances(
                        List.of(bank.getGlAccountId(), other.getGlAccountId(), untouched.getGlAccountId()),
                        TODAY.plusDays(1).atStartOfDay())
                .stream()
                .collect(Collectors.toMap(
                        JournalEntryLineRepository.AccountBalance::getGlAccountId,
                        JournalEntryLineRepository.AccountBalance::getBalance));

        assertThat(balances.get(bank.getGlAccountId())).isEqualByComparingTo("70.00");
        assertThat(balances.get(other.getGlAccountId())).isEqualByComparingTo("-70.00");
        assertThat(balances).as("an account with no lines is absent").doesNotContainKey(untouched.getGlAccountId());

        Map<UUID, BigDecimal> withTomorrow =
                journalEntryLines
                        .getAccountBalances(
                                List.of(bank.getGlAccountId()),
                                TODAY.plusDays(2).atStartOfDay())
                        .stream()
                        .collect(Collectors.toMap(
                                JournalEntryLineRepository.AccountBalance::getGlAccountId,
                                JournalEntryLineRepository.AccountBalance::getBalance));
        assertThat(withTomorrow.get(bank.getGlAccountId()))
                .as("the post-dated entry counts only once its day is in the bound")
                .isEqualByComparingTo("77.00");
    }

    private BankStatement statement(
            UUID account, LocalDate start, LocalDate end, String closing, BankStatementStatus status) {
        BankStatement statement = new BankStatement();
        statement.setGlAccountId(account);
        statement.setSourceKind(SourceKind.MANUAL_ENTRY);
        statement.setStartDate(start);
        statement.setEndDate(end);
        statement.setOpeningBalance(BigDecimal.ZERO);
        statement.setClosingBalance(new BigDecimal(closing));
        statement.setActivityTotal(new BigDecimal(closing));
        statement.setCurrency("USD");
        statement.setStatus(status);
        statement.setCreatedBy("cash-query-test");
        return statements.saveAndFlush(statement);
    }

    @Test
    @DisplayName("findLatestByGlAccountIdIn returns each account's latest-ending committed statement")
    void latestStatements() {
        GLAccount first = account("CQ-4", AccountType.ASSET, AccountSubtype.BANK_CASH);
        GLAccount second = account("CQ-5", AccountType.ASSET, AccountSubtype.BANK_CASH);
        statement(
                first.getGlAccountId(),
                LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 8, 31),
                "100.00",
                BankStatementStatus.COMMITTED);
        statement(
                first.getGlAccountId(),
                LocalDate.of(2026, 9, 1),
                LocalDate.of(2026, 9, 30),
                "250.00",
                BankStatementStatus.COMMITTED);
        statement(
                first.getGlAccountId(),
                LocalDate.of(2026, 10, 1),
                LocalDate.of(2026, 10, 31),
                "999.00",
                BankStatementStatus.SUPERSEDED);
        statement(
                second.getGlAccountId(),
                LocalDate.of(2026, 9, 1),
                LocalDate.of(2026, 9, 30),
                "70.00",
                BankStatementStatus.COMMITTED);

        List<BankStatement> latest = statements.findLatestByGlAccountIdIn(
                List.of(first.getGlAccountId(), second.getGlAccountId()), BankStatementStatus.COMMITTED);

        assertThat(latest)
                .extracting(
                        BankStatement::getGlAccountId,
                        s -> s.getClosingBalance().intValue())
                .containsExactlyInAnyOrder(
                        org.assertj.core.api.Assertions.tuple(first.getGlAccountId(), 250),
                        org.assertj.core.api.Assertions.tuple(second.getGlAccountId(), 70));
    }

    private void item(
            UUID account, OutstandingItemKind kind, String amount, LocalDate date, OutstandingItemStatus status) {
        BankReconciliationOutstandingItem item = new BankReconciliationOutstandingItem();
        item.setGlAccountId(account);
        item.setSide(OutstandingItemSide.LEDGER);
        item.setItemKind(kind);
        item.setSignedAmount(new BigDecimal(amount));
        item.setItemDate(date);
        item.setRegisteredInReconciliationId(UUID.randomUUID());
        item.setRegisteredBy("cash-query-test");
        item.setRegisteredAt(Instant.parse("2026-10-01T12:00:00Z"));
        item.setStatus(status);
        outstandingItems.saveAndFlush(item);
    }

    @Test
    @DisplayName("totalsSinceBaselineByGlAccountIdIn counts and sums open items by kind from the account's baseline")
    void outstandingTotals() {
        GLAccount withBaseline = account("CQ-6", AccountType.ASSET, AccountSubtype.BANK_CASH);
        GLAccount without = account("CQ-7", AccountType.ASSET, AccountSubtype.BANK_CASH);
        BankAccountProfile profile = new BankAccountProfile(withBaseline.getGlAccountId());
        profile.setCurrency("USD");
        profile.setReconciliationBaselineDate(LocalDate.of(2026, 9, 1));
        profiles.saveAndFlush(profile);
        UUID a = withBaseline.getGlAccountId();
        UUID b = without.getGlAccountId();
        item(
                a,
                OutstandingItemKind.DEPOSIT_IN_TRANSIT,
                "450.00",
                LocalDate.of(2026, 9, 20),
                OutstandingItemStatus.OPEN);
        item(a, OutstandingItemKind.DEPOSIT_IN_TRANSIT, "50.00", LocalDate.of(2026, 8, 20), OutstandingItemStatus.OPEN);
        item(
                a,
                OutstandingItemKind.OUTSTANDING_CHECK,
                "-120.00",
                LocalDate.of(2026, 9, 25),
                OutstandingItemStatus.OPEN);
        item(a, OutstandingItemKind.OUTSTANDING_CHECK, "-30.00", LocalDate.of(2026, 9, 26), OutstandingItemStatus.OPEN);
        item(a, OutstandingItemKind.OTHER_LEDGER_TIMING, "5.00", LocalDate.of(2026, 9, 26), OutstandingItemStatus.OPEN);
        item(b, OutstandingItemKind.DEPOSIT_IN_TRANSIT, "10.00", LocalDate.of(2026, 1, 2), OutstandingItemStatus.OPEN);
        item(
                b,
                OutstandingItemKind.DEPOSIT_IN_TRANSIT,
                "99.00",
                LocalDate.of(2026, 1, 2),
                OutstandingItemStatus.CLEARED);

        List<AccountKindTotals> totals = outstandingItems.totalsSinceBaselineByGlAccountIdIn(
                List.of(a, b),
                OutstandingItemStatus.OPEN,
                Set.of(OutstandingItemKind.DEPOSIT_IN_TRANSIT, OutstandingItemKind.OUTSTANDING_CHECK));

        assertThat(totals).hasSize(3);
        assertThat(find(totals, a, OutstandingItemKind.DEPOSIT_IN_TRANSIT).count())
                .isEqualTo(1);
        assertThat(find(totals, a, OutstandingItemKind.DEPOSIT_IN_TRANSIT).signedAmount())
                .isEqualByComparingTo("450.00");
        assertThat(find(totals, a, OutstandingItemKind.OUTSTANDING_CHECK).count())
                .isEqualTo(2);
        assertThat(find(totals, a, OutstandingItemKind.OUTSTANDING_CHECK).signedAmount())
                .isEqualByComparingTo("-150.00");
        assertThat(find(totals, b, OutstandingItemKind.DEPOSIT_IN_TRANSIT).count())
                .isEqualTo(1);
    }

    private static AccountKindTotals find(List<AccountKindTotals> totals, UUID account, OutstandingItemKind kind) {
        return totals.stream()
                .filter(t -> t.glAccountId().equals(account) && t.kind() == kind)
                .findFirst()
                .orElseThrow();
    }

    private APPayment payment(
            String ref,
            PaymentMethod method,
            APPaymentStatus status,
            String unapplied,
            LocalDate date,
            String currency) {
        APPayment payment = new APPayment(UUID.randomUUID());
        payment.setPaymentRef(ref);
        payment.setVendorId(UUID.randomUUID());
        payment.setGrossAmount(new BigDecimal("200.00"));
        payment.setUnappliedAmount(new BigDecimal(unapplied));
        payment.setCurrency(currency);
        payment.setStatus(status);
        payment.setPaymentMethod(method);
        payment.setPaymentDate(date);
        payment.setCreatedBy("cash-query-test");
        return apPayments.saveAndFlush(payment);
    }

    @Test
    @DisplayName("findUnappliedByMethodBefore finds aged cash payments with money left, oldest first")
    void unappliedCashPayments() {
        LocalDate cutoff = LocalDate.of(2026, 10, 3);
        APPayment newer = payment(
                "CQ-P1", PaymentMethod.CASH, APPaymentStatus.GL_POSTED, "55.00", LocalDate.of(2026, 10, 1), "USD");
        APPayment older = payment(
                "CQ-P2", PaymentMethod.CASH, APPaymentStatus.GL_POSTED, "145.00", LocalDate.of(2026, 9, 20), "USD");
        payment("CQ-P3", PaymentMethod.CASH, APPaymentStatus.GL_POSTED, "10.00", cutoff, "USD");
        payment("CQ-P4", PaymentMethod.CASH, APPaymentStatus.VOIDED, "10.00", LocalDate.of(2026, 9, 1), "USD");
        payment("CQ-P5", PaymentMethod.CASH, APPaymentStatus.GL_POSTED, "0.00", LocalDate.of(2026, 9, 1), "USD");
        payment("CQ-P6", PaymentMethod.ACH, APPaymentStatus.GL_POSTED, "10.00", LocalDate.of(2026, 9, 1), "USD");
        payment("CQ-P7", PaymentMethod.CASH, APPaymentStatus.GL_POSTED, "10.00", LocalDate.of(2026, 9, 1), "EUR");

        List<APPayment> found = apPayments.findUnappliedByMethodBefore(
                PaymentMethod.CASH, "USD", Set.of(APPaymentStatus.VOIDED, APPaymentStatus.RETURNED), cutoff);

        assertThat(found)
                .extracting(APPayment::getPaymentId)
                .containsExactly(older.getPaymentId(), newer.getPaymentId());
    }

    private ExtInvoice invoice(String party, String status) {
        return extInvoices.saveAndFlush(ExtInvoice.builder()
                .invoiceId(UUID.randomUUID())
                .invoiceNumber("CQ-" + UUID.randomUUID().toString().substring(0, 8))
                .status(status)
                .partyId(party)
                .total(new BigDecimal("10.00"))
                .updatedAt(Instant.parse("2026-10-17T10:00:00Z"))
                .build());
    }

    @Test
    @DisplayName("the invoice queries match parties trimmed and case-insensitive, as isWalkIn does")
    void invoiceQueries() {
        String cash = "0199a000-0000-7000-8000-00000000ca51";
        ExtInvoice walkIn = invoice("  " + cash.toUpperCase() + " ", "FINALIZED");
        ExtInvoice customer = invoice("0199a000-0000-7000-8000-00000000d001", "FINALIZED");
        ExtInvoice noParty = invoice(null, "POSTED");
        invoice("0199a000-0000-7000-8000-00000000d002", "DRAFT");

        assertThat(extInvoices.findNewestUpdateByPartyIdIn(Set.of(cash))).isNotNull();
        assertThat(extInvoices.findNewestUpdateByPartyIdIn(Set.of("0199a000-0000-7000-8000-00000000ffff")))
                .isNull();

        List<ExtInvoice> candidates =
                extInvoices.findByStatusInExcludingParties(Set.of("FINALIZED", "POSTED"), Set.of(cash));
        assertThat(candidates)
                .extracting(ExtInvoice::getInvoiceId)
                .contains(customer.getInvoiceId(), noParty.getInvoiceId())
                .doesNotContain(walkIn.getInvoiceId());
        assertThat(extInvoices.findByStatusInExcludingParties(Set.of("FINALIZED", "POSTED"), Set.of("-")))
                .extracting(ExtInvoice::getInvoiceId)
                .contains(walkIn.getInvoiceId(), customer.getInvoiceId(), noParty.getInvoiceId());
    }
}
