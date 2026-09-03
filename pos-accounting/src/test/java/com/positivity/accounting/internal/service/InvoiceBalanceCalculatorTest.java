package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.config.DatabaseDialectSupport;
import com.positivity.accounting.internal.dto.AgedReceivablesReport;
import com.positivity.accounting.internal.entity.ExtInvoice;
import com.positivity.accounting.internal.enums.CreditMemoStatus;
import com.positivity.accounting.internal.enums.CustomerCreditTransactionType;
import com.positivity.accounting.internal.enums.InvoiceStatus;
import com.positivity.accounting.internal.repository.APPaymentAllocationRepository;
import com.positivity.accounting.internal.repository.AccountingSequenceRepository;
import com.positivity.accounting.internal.repository.CreditMemoRepository;
import com.positivity.accounting.internal.repository.CreditMemoTaxRepository;
import com.positivity.accounting.internal.repository.CustomerCreditTransactionRepository;
import com.positivity.accounting.internal.repository.ExtInvoiceDepositCreditApplicationRepository;
import com.positivity.accounting.internal.repository.ExtInvoiceRepository;
import com.positivity.accounting.internal.repository.ExtInvoiceTaxRepository;
import com.positivity.accounting.internal.repository.GLAccountRepository;
import com.positivity.accounting.internal.repository.JournalEntryRepository;
import com.positivity.accounting.internal.repository.PaymentApplicationRepository;
import com.positivity.accounting.internal.repository.PaymentApplicationReversalRepository;
import com.positivity.accounting.internal.repository.StatementLineMappingRepository;
import com.positivity.accounting.internal.repository.VendorBillRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link InvoiceBalanceCalculator#balanceDue} (issue #1652): the deposit-credit
 * draw-down term added alongside the existing applied/reversed/credit-memo/customer-credit terms.
 *
 * <p>{@link AgedReceivablesThroughRealCalculator} additionally drives {@link
 * FinancialReportingServiceImpl#generateAgedReceivables} through a real {@code
 * InvoiceBalanceCalculator} (mocked repositories underneath, not a mocked calculator) so the
 * deposit-credit subtraction is proven end to end, not just at the calculator's own boundary.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("InvoiceBalanceCalculator (#1652)")
class InvoiceBalanceCalculatorTest {

    private static final UUID INVOICE_ID = UUID.fromString("00000000-0000-7000-8000-000000000001");

    @Mock
    private ExtInvoiceRepository extInvoiceRepository;

    @Mock
    private PaymentApplicationRepository paymentApplicationRepository;

    @Mock
    private PaymentApplicationReversalRepository reversalRepository;

    @Mock
    private CreditMemoRepository creditMemoRepository;

    @Mock
    private CustomerCreditTransactionRepository creditTransactionRepository;

    @Mock
    private ExtInvoiceDepositCreditApplicationRepository depositCreditApplicationRepository;

    private InvoiceBalanceCalculator calculator;

    @BeforeEach
    void setUp() {
        calculator = new InvoiceBalanceCalculator(
                extInvoiceRepository,
                paymentApplicationRepository,
                reversalRepository,
                creditMemoRepository,
                creditTransactionRepository,
                depositCreditApplicationRepository);
    }

    @Test
    @DisplayName("full formula: applied, reversed, credit memo, customer credit, and deposit credit all non-zero")
    void balanceDueSubtractsAllFiveTerms() {
        ExtInvoice invoice = invoice(new BigDecimal("10000.00"));
        when(paymentApplicationRepository.sumAppliedAmountByInvoiceId(INVOICE_ID))
                .thenReturn(new BigDecimal("6000.00"));
        when(reversalRepository.sumReversedAmountByInvoiceId(INVOICE_ID)).thenReturn(new BigDecimal("500.00"));
        when(creditMemoRepository.sumCreditedAmountByInvoiceIdAndStatus(INVOICE_ID, CreditMemoStatus.POSTED))
                .thenReturn(new BigDecimal("300.00"));
        when(creditTransactionRepository.sumAmountByInvoiceIdAndType(
                        INVOICE_ID, CustomerCreditTransactionType.APPLICATION))
                .thenReturn(new BigDecimal("200.00"));
        when(depositCreditApplicationRepository.sumAmountAppliedByInvoiceId(INVOICE_ID))
                .thenReturn(new BigDecimal("1000.00"));

        // 10000 - (6000 - 500) - 300 - 200 - 1000 = 3000
        BigDecimal balanceDue = calculator.balanceDue(invoice);

        assertThat(balanceDue).isEqualByComparingTo("3000.00");
    }

    @Test
    @DisplayName(
            "partially deposit-settled invoice (Track B fixture): 2500 total, 1500 cash applied, 500 deposit -> 500 due, PARTIALLY_PAID")
    void partiallyDepositSettledInvoiceReducesBalance() {
        ExtInvoice invoice = invoice(new BigDecimal("2500.00"));
        stubZeroExcept(invoice);
        when(paymentApplicationRepository.sumAppliedAmountByInvoiceId(INVOICE_ID))
                .thenReturn(new BigDecimal("1500.00"));
        when(depositCreditApplicationRepository.sumAmountAppliedByInvoiceId(INVOICE_ID))
                .thenReturn(new BigDecimal("500.00"));

        BigDecimal balanceDue = calculator.balanceDue(invoice);

        assertThat(balanceDue).isEqualByComparingTo("500.00");
        assertThat(calculator.deriveArStatus(invoice, balanceDue)).isEqualTo(InvoiceStatus.PARTIALLY_PAID);
    }

    @Test
    @DisplayName("wholly deposit-settled invoice: total == deposit applied -> 0 due, PAID_IN_FULL")
    void whollyDepositSettledInvoiceIsPaidInFull() {
        ExtInvoice invoice = invoice(new BigDecimal("500.00"));
        stubZeroExcept(invoice);
        when(depositCreditApplicationRepository.sumAmountAppliedByInvoiceId(INVOICE_ID))
                .thenReturn(new BigDecimal("500.00"));

        BigDecimal balanceDue = calculator.balanceDue(invoice);

        assertThat(balanceDue).isEqualByComparingTo("0.00");
        assertThat(calculator.deriveArStatus(invoice, balanceDue)).isEqualTo(InvoiceStatus.PAID_IN_FULL);
    }

    @Test
    @DisplayName("no deposit-credit rows: behaviour is unchanged from the pre-#1652 formula")
    void noDepositRowsLeavesBalanceUnchanged() {
        ExtInvoice invoice = invoice(new BigDecimal("1000.00"));
        stubZeroExcept(invoice);
        when(paymentApplicationRepository.sumAppliedAmountByInvoiceId(INVOICE_ID))
                .thenReturn(new BigDecimal("400.00"));
        // depositCreditApplicationRepository stubbed to zero by stubZeroExcept — no rows exist.

        BigDecimal balanceDue = calculator.balanceDue(invoice);

        assertThat(balanceDue).isEqualByComparingTo("600.00");
        assertThat(calculator.deriveArStatus(invoice, balanceDue)).isEqualTo(InvoiceStatus.PARTIALLY_PAID);
    }

    /** Stubs every sum repository to zero so a test can override only the term(s) it cares about. */
    private void stubZeroExcept(ExtInvoice invoice) {
        when(paymentApplicationRepository.sumAppliedAmountByInvoiceId(invoice.getInvoiceId()))
                .thenReturn(BigDecimal.ZERO);
        when(reversalRepository.sumReversedAmountByInvoiceId(invoice.getInvoiceId()))
                .thenReturn(BigDecimal.ZERO);
        when(creditMemoRepository.sumCreditedAmountByInvoiceIdAndStatus(
                        invoice.getInvoiceId(), CreditMemoStatus.POSTED))
                .thenReturn(BigDecimal.ZERO);
        when(creditTransactionRepository.sumAmountByInvoiceIdAndType(
                        invoice.getInvoiceId(), CustomerCreditTransactionType.APPLICATION))
                .thenReturn(BigDecimal.ZERO);
        when(depositCreditApplicationRepository.sumAmountAppliedByInvoiceId(invoice.getInvoiceId()))
                .thenReturn(BigDecimal.ZERO);
    }

    private static ExtInvoice invoice(BigDecimal total) {
        return ExtInvoice.builder()
                .invoiceId(INVOICE_ID)
                .partyId(UUID.randomUUID().toString())
                .status("POSTED")
                .total(total)
                .build();
    }

    /**
     * Proves the deposit-credit subtraction end to end through {@link
     * FinancialReportingServiceImpl#generateAgedReceivables} (issue #1652 acceptance: "a test
     * covering a partially deposit-settled invoice through aged receivables"). {@link
     * FinancialReportingG2ServiceTest} mocks {@code invoiceBalanceCalculator.balanceDue(...)}
     * directly, so it cannot exercise the real subtraction — this nested class wires a real
     * {@link InvoiceBalanceCalculator} (with mocked repositories underneath) into a real {@link
     * FinancialReportingServiceImpl} instead.
     */
    @Nested
    @DisplayName("aged receivables through a real InvoiceBalanceCalculator (#1652)")
    @ExtendWith(MockitoExtension.class)
    class AgedReceivablesThroughRealCalculator {

        private static final Instant FIXED_NOW = Instant.parse("2026-07-01T12:00:00Z");
        private static final LocalDate AS_OF = LocalDate.of(2026, 6, 30);

        @Mock
        private JournalEntryRepository journalEntryRepository;

        @Mock
        private StatementLineMappingRepository statementLineMappingRepository;

        @Mock
        private AccountingSequenceRepository accountingSequenceRepository;

        @Mock
        private GLAccountRepository glAccountRepository;

        @Mock
        private ExtInvoiceRepository extInvoiceRepository;

        @Mock
        private ExtInvoiceTaxRepository extInvoiceTaxRepository;

        @Mock
        private CreditMemoRepository creditMemoRepository;

        @Mock
        private CreditMemoTaxRepository creditMemoTaxRepository;

        @Mock
        private VendorBillRepository vendorBillRepository;

        @Mock
        private APPaymentAllocationRepository apPaymentAllocationRepository;

        @Mock
        private PaymentApplicationRepository paymentApplicationRepository;

        @Mock
        private PaymentApplicationReversalRepository reversalRepository;

        @Mock
        private CustomerCreditTransactionRepository creditTransactionRepository;

        @Mock
        private ExtInvoiceDepositCreditApplicationRepository depositCreditApplicationRepository;

        @Mock
        private DatabaseDialectSupport databaseDialectSupport;

        private FinancialReportingServiceImpl service;

        @BeforeEach
        void setUp() {
            InvoiceBalanceCalculator realCalculator = new InvoiceBalanceCalculator(
                    extInvoiceRepository,
                    paymentApplicationRepository,
                    reversalRepository,
                    creditMemoRepository,
                    creditTransactionRepository,
                    depositCreditApplicationRepository);
            service = new FinancialReportingServiceImpl(
                    journalEntryRepository,
                    statementLineMappingRepository,
                    accountingSequenceRepository,
                    glAccountRepository,
                    extInvoiceRepository,
                    extInvoiceTaxRepository,
                    creditMemoRepository,
                    creditMemoTaxRepository,
                    vendorBillRepository,
                    apPaymentAllocationRepository,
                    realCalculator,
                    databaseDialectSupport,
                    Clock.fixed(FIXED_NOW, ZoneOffset.UTC));
        }

        @Test
        @DisplayName(
                "Track B fixture: 2500 total, 1500 cash, 500 deposit -> aged receivables reports 500 open, not 1000")
        void agedReceivablesReflectsDepositRelief() {
            UUID invoiceId = UUID.fromString("00000000-0000-7000-8000-000000000002");
            UUID customerId = UUID.fromString("00000000-0000-7000-8000-0000000000c2");
            ExtInvoice invoice = ExtInvoice.builder()
                    .invoiceId(invoiceId)
                    .partyId(customerId.toString())
                    .status("POSTED")
                    .total(new BigDecimal("2500.00"))
                    .invoiceCreatedAt(AS_OF.minusDays(10).atStartOfDay().toInstant(ZoneOffset.UTC))
                    .dueDate(AS_OF)
                    .build();
            when(extInvoiceRepository.findByStatusIn(any())).thenReturn(List.of(invoice));
            when(paymentApplicationRepository.sumAppliedAmountByInvoiceId(invoiceId))
                    .thenReturn(new BigDecimal("1500.00"));
            when(reversalRepository.sumReversedAmountByInvoiceId(invoiceId)).thenReturn(BigDecimal.ZERO);
            when(creditMemoRepository.sumCreditedAmountByInvoiceIdAndStatus(invoiceId, CreditMemoStatus.POSTED))
                    .thenReturn(BigDecimal.ZERO);
            when(creditTransactionRepository.sumAmountByInvoiceIdAndType(
                            invoiceId, CustomerCreditTransactionType.APPLICATION))
                    .thenReturn(BigDecimal.ZERO);
            when(depositCreditApplicationRepository.sumAmountAppliedByInvoiceId(invoiceId))
                    .thenReturn(new BigDecimal("500.00"));

            AgedReceivablesReport report = service.generateAgedReceivables(AS_OF);

            // Pre-#1652 this would have reported 1000.00 (2500 - 1500, deposit ignored).
            assertThat(report.getTotals().getTotalOutstanding()).isEqualByComparingTo("500.00");
            assertThat(report.getTotals().getCurrent()).isEqualByComparingTo("500.00");
            assertThat(report.getRows()).hasSize(1);
            assertThat(report.getRows().get(0).getCustomerId()).isEqualTo(customerId);
            assertThat(report.getRows().get(0).getTotalOutstanding()).isEqualByComparingTo("500.00");
        }
    }
}
