package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.config.LedgerCurrency;
import com.positivity.accounting.internal.dto.CustomerOpenInvoicesPage;
import com.positivity.accounting.internal.dto.OpenInvoiceRow;
import com.positivity.accounting.internal.dto.ResolvedDisplayReference;
import com.positivity.accounting.internal.dto.UnappliedPaymentRow;
import com.positivity.accounting.internal.dto.UnappliedPaymentsPage;
import com.positivity.accounting.internal.entity.ExtInvoice;
import com.positivity.accounting.internal.entity.ReceivablePayment;
import com.positivity.accounting.internal.entity.ReceivablePayment.ReceivablePaymentStatus;
import com.positivity.accounting.internal.enums.DisplayReferenceType;
import com.positivity.accounting.internal.repository.CreditMemoRepository;
import com.positivity.accounting.internal.repository.CustomerCreditTransactionRepository;
import com.positivity.accounting.internal.repository.ExtInvoiceDepositCreditApplicationRepository;
import com.positivity.accounting.internal.repository.ExtInvoiceRepository;
import com.positivity.accounting.internal.repository.InvoiceAmount;
import com.positivity.accounting.internal.repository.PaymentApplicationRepository;
import com.positivity.accounting.internal.repository.PaymentApplicationReversalRepository;
import com.positivity.accounting.internal.repository.ReceivablePaymentRepository;
import com.positivity.accounting.internal.repository.ReceivablePaymentTotals;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * Filters, paging, summaries and display values of the receivables worklist reads (#2502), over a
 * real {@link InvoiceBalanceCalculator} and {@link UnappliedPaymentSuggester} with mocked
 * repositories.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReceivablesWorklistServiceImpl (#2502)")
class ReceivablesWorklistServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-10-06T12:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 6);
    private static final UUID CUSTOMER_A = id(0xA);
    private static final UUID CUSTOMER_B = id(0xB);

    @Mock
    private ReceivablePaymentRepository payments;

    @Mock
    private ExtInvoiceRepository invoices;

    @Mock
    private PaymentApplicationRepository applications;

    @Mock
    private PaymentApplicationReversalRepository reversals;

    @Mock
    private CreditMemoRepository creditMemos;

    @Mock
    private CustomerCreditTransactionRepository creditTransactions;

    @Mock
    private ExtInvoiceDepositCreditApplicationRepository depositApplications;

    @Mock
    private DisplayReferenceResolver resolver;

    private ReceivablesWorklistServiceImpl service;

    private static UUID id(int n) {
        return UUID.fromString(String.format("0199a000-0000-7000-8000-%012d", n));
    }

    @BeforeEach
    void setUp() {
        InvoiceBalanceCalculator calculator = new InvoiceBalanceCalculator(
                invoices, applications, reversals, creditMemos, creditTransactions, depositApplications);
        service = new ReceivablesWorklistServiceImpl(
                payments,
                invoices,
                calculator,
                resolver,
                new UnappliedPaymentSuggester(),
                new LedgerCurrency("USD"),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static ReceivablePayment payment(int n, UUID customer, String unapplied, Instant clearedAt) {
        ReceivablePayment payment = new ReceivablePayment();
        payment.setPaymentId(id(100 + n));
        payment.setCustomerId(customer);
        payment.setCurrency("USD");
        payment.setTotalAmount(new BigDecimal(unapplied));
        payment.setUnappliedAmount(new BigDecimal(unapplied));
        payment.setStatus(ReceivablePaymentStatus.AVAILABLE);
        payment.setClearedAt(clearedAt);
        return payment;
    }

    private static ExtInvoice invoice(int n, UUID customer, String total, LocalDate dueDate) {
        return ExtInvoice.builder()
                .invoiceId(id(n))
                .invoiceNumber("INV-" + n)
                .partyId(customer.toString())
                .status("POSTED")
                .total(new BigDecimal(total))
                .invoiceCreatedAt(Instant.parse("2026-09-01T10:00:00Z"))
                .dueDate(dueDate)
                .build();
    }

    @Nested
    @DisplayName("unapplied payments")
    class UnappliedPayments {

        @Test
        @DisplayName("lists AVAILABLE payments oldest first with totals over the whole filter (AC1)")
        void listsAvailableOldestFirstWithSummary() {
            ReceivablePayment first = payment(1, CUSTOMER_A, "100.0000", NOW.minusSeconds(300));
            when(payments.findByStatus(eq(ReceivablePaymentStatus.AVAILABLE), any(Pageable.class)))
                    .thenAnswer(call -> new PageImpl<>(List.of(first), call.getArgument(1), 3));
            when(payments.totalsByStatus(ReceivablePaymentStatus.AVAILABLE))
                    .thenReturn(new ReceivablePaymentTotals(3, new BigDecimal("650.0000")));

            UnappliedPaymentsPage page = service.listUnappliedPayments(null, 0, 1);

            ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
            verify(payments).findByStatus(eq(ReceivablePaymentStatus.AVAILABLE), pageable.capture());
            assertThat(pageable.getValue().getSort())
                    .isEqualTo(Sort.by(Sort.Order.asc("clearedAt"), Sort.Order.asc("paymentId")));
            assertThat(page.getItems())
                    .extracting(UnappliedPaymentRow::getPaymentId)
                    .containsExactly(first.getPaymentId());
            assertThat(page.getTotalElements()).isEqualTo(3);
            assertThat(page.getTotalPages()).isEqualTo(3);
            assertThat(page.getSummary().getCount()).isEqualTo(3);
            assertThat(page.getSummary().getTotalUnappliedAmount()).isEqualByComparingTo("650.00");
            assertThat(page.getSummary().getTotalUnappliedAmount().scale()).isEqualTo(2);
            assertThat(page.getSummary().getCurrency()).isEqualTo("USD");
            assertThat(page.getSummary().getAsOf()).isEqualTo(NOW);
            assertThat(page.getItems().getFirst().getUnappliedAmount()).isEqualByComparingTo("100.00");
            assertThat(page.getItems().getFirst().getUnappliedAmount().scale()).isEqualTo(2);
            verify(payments, never()).findByStatusAndCustomerId(any(), any(), any());
        }

        @Test
        @DisplayName("filters by customer through the customer queries")
        void filtersByCustomer() {
            when(payments.findByStatusAndCustomerId(
                            eq(ReceivablePaymentStatus.AVAILABLE), eq(CUSTOMER_B), any(Pageable.class)))
                    .thenAnswer(call -> new PageImpl<>(List.of(), call.getArgument(2), 0));
            when(payments.totalsByStatusAndCustomerId(ReceivablePaymentStatus.AVAILABLE, CUSTOMER_B))
                    .thenReturn(new ReceivablePaymentTotals(0, BigDecimal.ZERO));

            UnappliedPaymentsPage page = service.listUnappliedPayments(CUSTOMER_B, 0, 25);

            assertThat(page.getItems()).isEmpty();
            assertThat(page.getSummary().getCount()).isZero();
            assertThat(page.getSummary().getTotalUnappliedAmount()).isEqualByComparingTo("0.00");
            verify(payments, never()).findByStatus(any(), any(Pageable.class));
            verify(invoices, never()).findByPartyIdInAndStatusIn(anyCollection(), anyCollection());
        }

        @Test
        @DisplayName("suggests from the payment's own customer only, and resolves display values (BR-4, AC12)")
        void suggestsPerCustomer() {
            ReceivablePayment ofA = payment(1, CUSTOMER_A, "200.00", NOW.minusSeconds(600));
            ofA.setPaymentMethod("CARD");
            ofA.setSourceInvoiceId(id(99));
            ReceivablePayment ofB = payment(2, CUSTOMER_B, "200.00", NOW.minusSeconds(300));
            when(payments.findByStatus(eq(ReceivablePaymentStatus.AVAILABLE), any(Pageable.class)))
                    .thenAnswer(call -> new PageImpl<>(List.of(ofA, ofB), call.getArgument(1), 2));
            when(payments.totalsByStatus(ReceivablePaymentStatus.AVAILABLE))
                    .thenReturn(new ReceivablePaymentTotals(2, new BigDecimal("400.00")));
            when(resolver.resolve(eq(DisplayReferenceType.CUSTOMER), anyCollection()))
                    .thenReturn(Map.of(CUSTOMER_A, new ResolvedDisplayReference("Rivera Trucking", "CUST-00412")));
            when(resolver.resolve(eq(DisplayReferenceType.INVOICE), anyCollection()))
                    .thenReturn(Map.of(id(99), ResolvedDisplayReference.ofReference("INV-99")));
            // Only B has an invoice of 200.00; A's single open invoice is 150.00.
            when(invoices.findByPartyIdInAndStatusIn(anyCollection(), anyCollection()))
                    .thenReturn(
                            List.of(invoice(1, CUSTOMER_A, "150.00", TODAY), invoice(2, CUSTOMER_B, "200.00", TODAY)));

            UnappliedPaymentsPage page = service.listUnappliedPayments(null, 0, 25);

            UnappliedPaymentRow rowA = page.getItems().get(0);
            UnappliedPaymentRow rowB = page.getItems().get(1);
            assertThat(rowA.getCustomerDisplayName()).isEqualTo("Rivera Trucking");
            assertThat(rowA.getCustomerReference()).isEqualTo("CUST-00412");
            assertThat(rowA.getPaymentMethod()).isEqualTo("CARD");
            assertThat(rowA.getReceivedAt()).isEqualTo(ofA.getClearedAt());
            assertThat(rowA.getSourceInvoiceNumber()).isEqualTo("INV-99");
            assertThat(rowA.getSuggestion().getInvoices()).isEmpty();
            assertThat(rowA.getSuggestion().getReasons()).containsExactly(UnappliedPaymentSuggester.SAME_CUSTOMER);
            // B is missing from the customer replica: display fields null, never the id (AC12).
            assertThat(rowB.getCustomerDisplayName()).isNull();
            assertThat(rowB.getCustomerReference()).isNull();
            assertThat(rowB.getSourceInvoiceNumber()).isNull();
            assertThat(rowB.getSuggestion().getInvoices())
                    .singleElement()
                    .satisfies(invoice -> assertThat(invoice.getInvoiceId()).isEqualTo(id(2)));

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Collection<String>> parties = ArgumentCaptor.forClass(Collection.class);
            verify(invoices).findByPartyIdInAndStatusIn(parties.capture(), anyCollection());
            assertThat(parties.getValue()).containsExactlyInAnyOrder(CUSTOMER_A.toString(), CUSTOMER_B.toString());
        }
    }

    @Nested
    @DisplayName("open invoices")
    class OpenInvoices {

        @Test
        @DisplayName("keeps positive balances only, oldest first, with overdue days and a summary (AC6, AC7)")
        void openInvoicesOldestFirst() {
            ExtInvoice dueYesterday = invoice(1, CUSTOMER_A, "80.00", TODAY.minusDays(1));
            ExtInvoice dueLater = invoice(2, CUSTOMER_A, "50.00", TODAY.plusDays(10));
            ExtInvoice paid = invoice(3, CUSTOMER_A, "40.00", TODAY.minusDays(5));
            ExtInvoice noDueDate = invoice(4, CUSTOMER_A, "20.00", null);
            when(invoices.findByPartyIdInAndStatusIn(
                            List.of(CUSTOMER_A.toString()), InvoiceBalanceCalculator.AR_ELIGIBLE_STATUSES))
                    .thenReturn(List.of(dueLater, paid, dueYesterday, noDueDate));
            when(applications.sumAppliedAmountByInvoiceIdIn(anyCollection()))
                    .thenReturn(List.of(
                            new InvoiceAmount(id(3), new BigDecimal("40.00")),
                            new InvoiceAmount(id(2), new BigDecimal("10.00"))));

            CustomerOpenInvoicesPage page = service.listOpenInvoices(CUSTOMER_A, 0, 100);

            // OLDEST_FIRST: due date, else finalizedAt, nulls last — the no-due-date, never-finalized
            // replica row sorts last; for overdue it ages from its document date, 2026-09-01.
            assertThat(page.getItems()).extracting(OpenInvoiceRow::getInvoiceId).containsExactly(id(1), id(2), id(4));
            OpenInvoiceRow yesterday = page.getItems().get(0);
            OpenInvoiceRow later = page.getItems().get(1);
            OpenInvoiceRow noDue = page.getItems().get(2);
            assertThat(yesterday.isOverdue()).isTrue();
            assertThat(yesterday.getDaysOverdue()).isEqualTo(1);
            assertThat(yesterday.getArStatus()).isEqualTo("OPEN");
            assertThat(noDue.getDocumentDate()).isEqualTo(LocalDate.of(2026, 9, 1));
            assertThat(noDue.getDueDate()).isNull();
            assertThat(noDue.getDaysOverdue()).isEqualTo(35);
            assertThat(later.isOverdue()).isFalse();
            assertThat(later.getDaysOverdue()).isZero();
            assertThat(later.getBalanceDue()).isEqualByComparingTo("40.00");
            assertThat(later.getArStatus()).isEqualTo("PARTIALLY_PAID");
            assertThat(later.getCurrency()).isEqualTo("USD");
            assertThat(page.getSummary().getCount()).isEqualTo(3);
            assertThat(page.getSummary().getTotalBalanceDue()).isEqualByComparingTo("140.00");
            assertThat(page.getSummary().getOverdueCount()).isEqualTo(2);
            assertThat(page.getSummary().getOverdueBalanceDue()).isEqualByComparingTo("100.00");
            assertThat(page.getSummary().getAsOf()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("AC5: 500.00 less two 50.00 applications with one reversed in full, a 30.00 memo, a 20.00 credit"
                + " and a 40.00 deposit is 360.00")
        void balanceNetsEveryTerm() {
            when(invoices.findByPartyIdInAndStatusIn(anyCollection(), anyCollection()))
                    .thenReturn(List.of(invoice(1, CUSTOMER_A, "500.00", TODAY)));
            // Two 50.00 applications (grouped: 100.00) and one of them reversed in full (50.00).
            when(applications.sumAppliedAmountByInvoiceIdIn(anyCollection()))
                    .thenReturn(List.of(new InvoiceAmount(id(1), new BigDecimal("100.00"))));
            when(reversals.sumReversedAmountByInvoiceIdIn(anyCollection()))
                    .thenReturn(List.of(new InvoiceAmount(id(1), new BigDecimal("50.00"))));
            when(creditMemos.sumCreditedAmountByInvoiceIdInAndStatus(anyCollection(), any()))
                    .thenReturn(List.of(new InvoiceAmount(id(1), new BigDecimal("30.00"))));
            when(creditTransactions.sumAmountByInvoiceIdInAndType(anyCollection(), any()))
                    .thenReturn(List.of(new InvoiceAmount(id(1), new BigDecimal("20.00"))));
            when(depositApplications.sumAmountAppliedByInvoiceIdIn(anyCollection()))
                    .thenReturn(List.of(new InvoiceAmount(id(1), new BigDecimal("40.00"))));

            CustomerOpenInvoicesPage page = service.listOpenInvoices(CUSTOMER_A, 0, 100);

            assertThat(page.getItems()).singleElement().satisfies(row -> {
                assertThat(row.getBalanceDue()).isEqualByComparingTo("360.00");
                assertThat(row.getArStatus()).isEqualTo("PARTIALLY_PAID");
            });
        }

        @Test
        @DisplayName("pages after ordering; the summary still covers every open invoice")
        void pagesAfterOrdering() {
            when(invoices.findByPartyIdInAndStatusIn(anyCollection(), anyCollection()))
                    .thenReturn(List.of(
                            invoice(3, CUSTOMER_A, "30.00", TODAY.plusDays(3)),
                            invoice(1, CUSTOMER_A, "10.00", TODAY.plusDays(1)),
                            invoice(2, CUSTOMER_A, "20.00", TODAY.plusDays(2))));

            CustomerOpenInvoicesPage second = service.listOpenInvoices(CUSTOMER_A, 1, 2);
            CustomerOpenInvoicesPage beyond = service.listOpenInvoices(CUSTOMER_A, 5, 2);

            assertThat(second.getItems())
                    .extracting(OpenInvoiceRow::getInvoiceId)
                    .containsExactly(id(3));
            assertThat(second.getTotalElements()).isEqualTo(3);
            assertThat(second.getTotalPages()).isEqualTo(2);
            assertThat(second.getSummary().getTotalBalanceDue()).isEqualByComparingTo("60.00");
            assertThat(beyond.getItems()).isEmpty();
        }

        @Test
        @DisplayName("a customer unknown to accounting gets an empty page, not an error")
        void unknownCustomerIsEmpty() {
            CustomerOpenInvoicesPage page = service.listOpenInvoices(CUSTOMER_B, 0, 100);

            assertThat(page.getItems()).isEmpty();
            assertThat(page.getTotalElements()).isZero();
            assertThat(page.getTotalPages()).isZero();
            assertThat(page.getSummary().getTotalBalanceDue()).isEqualByComparingTo("0.00");
        }
    }
}
