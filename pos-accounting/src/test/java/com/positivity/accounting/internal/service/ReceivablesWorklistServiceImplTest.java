package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
import com.positivity.accounting.internal.enums.AccountingEventStatus;
import com.positivity.accounting.internal.enums.DisplayReferenceType;
import com.positivity.accounting.internal.enums.SourceInvoiceState;
import com.positivity.accounting.internal.repository.AccountingEventOutcome;
import com.positivity.accounting.internal.repository.AccountingEventRepository;
import com.positivity.accounting.internal.repository.CreditMemoRepository;
import com.positivity.accounting.internal.repository.CustomerCreditTransactionRepository;
import com.positivity.accounting.internal.repository.ExtInvoiceDepositCreditApplicationRepository;
import com.positivity.accounting.internal.repository.ExtInvoiceRepository;
import com.positivity.accounting.internal.repository.InvoiceAmount;
import com.positivity.accounting.internal.repository.PaymentApplicationRepository;
import com.positivity.accounting.internal.repository.PaymentApplicationReversalRepository;
import com.positivity.accounting.internal.repository.ReceivablePaymentRepository;
import com.positivity.accounting.internal.repository.ReceivablePaymentTotals;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
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

    @Mock
    private com.positivity.accounting.internal.repository.ExtCustomerPartyRepository parties;

    @Mock
    private AccountingEventRepository events;

    private ReceivablesWorklistServiceImpl service;

    private static UUID id(int n) {
        return UUID.fromString(String.format("0199a000-0000-7000-8000-%012d", n));
    }

    @BeforeEach
    void setUp() {
        InvoiceBalanceCalculator calculator = new InvoiceBalanceCalculator(
                invoices, applications, reversals, creditMemos, creditTransactions, depositApplications, parties);
        service = new ReceivablesWorklistServiceImpl(
                payments,
                invoices,
                applications,
                events,
                calculator,
                resolver,
                new UnappliedPaymentSuggester(),
                new LedgerCurrency("USD"),
                Clock.fixed(NOW, ZoneOffset.UTC),
                TestZoneResolvers.utc(Clock.fixed(NOW, ZoneOffset.UTC)));
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
            // The source invoice is read once, for its number and its state (#2563).
            when(invoices.findAllById(any())).thenReturn(List.of(invoice(99, CUSTOMER_A, "50.00", TODAY)));
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
            assertThat(rowA.getSourceInvoiceState()).isEqualTo(SourceInvoiceState.OPEN);
            assertThat(rowA.getSuggestion().getInvoices()).isEmpty();
            assertThat(rowA.getSuggestion().getReasons()).containsExactly(UnappliedPaymentSuggester.SAME_CUSTOMER);
            // B is missing from the customer replica: display fields null, never the id (AC12).
            assertThat(rowB.getCustomerDisplayName()).isNull();
            assertThat(rowB.getCustomerReference()).isNull();
            assertThat(rowB.getSourceInvoiceNumber()).isNull();
            assertThat(rowB.getSourceInvoiceState()).isNull();
            assertThat(rowB.isPossibleDuplicate()).isFalse();
            assertThat(rowB.getSuggestion().getInvoices())
                    .singleElement()
                    .satisfies(invoice -> assertThat(invoice.getInvoiceId()).isEqualTo(id(2)));

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Collection<String>> parties = ArgumentCaptor.forClass(Collection.class);
            verify(invoices).findByPartyIdInAndStatusIn(parties.capture(), anyCollection());
            assertThat(parties.getValue()).containsExactlyInAnyOrder(CUSTOMER_A.toString(), CUSTOMER_B.toString());
        }
    }

    /** One page of the given payments, unfiltered. */
    private UnappliedPaymentsPage list(ReceivablePayment... rows) {
        when(payments.findByStatus(eq(ReceivablePaymentStatus.AVAILABLE), any(Pageable.class)))
                .thenAnswer(call -> new PageImpl<>(List.of(rows), call.getArgument(1), rows.length));
        when(payments.totalsByStatus(ReceivablePaymentStatus.AVAILABLE))
                .thenReturn(new ReceivablePaymentTotals(rows.length, BigDecimal.ZERO));
        return service.listUnappliedPayments(null, 0, 25);
    }

    /** A payment of {@code total} with {@code unapplied} left, taken against invoice {@code id(invoice)}. */
    private static ReceivablePayment takenAgainst(int n, int invoice, String total, String unapplied) {
        ReceivablePayment payment = payment(n, CUSTOMER_A, unapplied, NOW.minusSeconds(1000 - n));
        payment.setTotalAmount(new BigDecimal(total));
        payment.setSourceInvoiceId(id(invoice));
        return payment;
    }

    private static ExtInvoice withStatus(ExtInvoice invoice, String status) {
        invoice.setStatus(status);
        return invoice;
    }

    private static AccountingEventOutcome outcome(
            ReceivablePayment payment, AccountingEventStatus status, String reason, Instant receivedAt) {
        return new AccountingEventOutcome(
                UUID.randomUUID(), payment.getPaymentId().toString(), status, reason, receivedAt);
    }

    @Nested
    @DisplayName("the source invoice's state and the possible-duplicate flag (#2563)")
    class SourceInvoice {

        @Test
        @DisplayName("AC1, case g: an invoice already paid by another payment is PAID and the payment a possible"
                + " duplicate")
        void caseG() {
            ReceivablePayment payment = takenAgainst(1, 10, "100.00", "100.00");
            when(invoices.findAllById(any())).thenReturn(List.of(invoice(10, CUSTOMER_A, "100.00", TODAY)));
            when(applications.sumAppliedAmountByInvoiceIdIn(anyCollection()))
                    .thenReturn(List.of(new InvoiceAmount(id(10), new BigDecimal("100.00"))));

            UnappliedPaymentRow row = list(payment).getItems().getFirst();

            assertThat(row.getSourceInvoiceState()).isEqualTo(SourceInvoiceState.PAID);
            assertThat(row.isPossibleDuplicate()).isTrue();
            assertThat(row.getSourceInvoiceNumber()).isEqualTo("INV-10");
            verify(applications).findPaymentIdsWithLiveApplicationToSourceInvoice(List.of(payment.getPaymentId()));
        }

        @Test
        @DisplayName("a balance below the currency's minor unit is no open balance: PAID (the open-invoice rule)")
        void subCentBalanceIsPaid() {
            ReceivablePayment payment = takenAgainst(1, 10, "100.00", "100.00");
            when(invoices.findAllById(any())).thenReturn(List.of(invoice(10, CUSTOMER_A, "100.00", TODAY)));
            when(applications.sumAppliedAmountByInvoiceIdIn(anyCollection()))
                    .thenReturn(List.of(new InvoiceAmount(id(10), new BigDecimal("99.996"))));

            UnappliedPaymentRow row = list(payment).getItems().getFirst();

            assertThat(row.getSourceInvoiceState()).isEqualTo(SourceInvoiceState.PAID);
            assertThat(row.isPossibleDuplicate()).isTrue();
        }

        @Test
        @DisplayName("AC2: a payment that paid part of its invoice, which another payment finished, is not a duplicate")
        void partiallyAppliedBySamePayment() {
            ReceivablePayment payment = takenAgainst(1, 10, "100.00", "40.00");
            when(invoices.findAllById(any())).thenReturn(List.of(invoice(10, CUSTOMER_A, "100.00", TODAY)));
            // 60.00 from this payment and 40.00 from another.
            when(applications.sumAppliedAmountByInvoiceIdIn(anyCollection()))
                    .thenReturn(List.of(new InvoiceAmount(id(10), new BigDecimal("100.00"))));
            when(applications.findPaymentIdsWithLiveApplicationToSourceInvoice(anyCollection()))
                    .thenReturn(List.of(payment.getPaymentId()));

            UnappliedPaymentRow row = list(payment).getItems().getFirst();

            assertThat(row.getSourceInvoiceState()).isEqualTo(SourceInvoiceState.PAID);
            assertThat(row.isPossibleDuplicate()).isFalse();
        }

        @Test
        @DisplayName("AC2: a payment that paid its whole invoice and has a remainder waiting (the walk-in excess) is"
                + " not a duplicate")
        void fullyPaidBySamePaymentWithRemainder() {
            ReceivablePayment payment = takenAgainst(1, 10, "120.00", "20.00");
            when(invoices.findAllById(any())).thenReturn(List.of(invoice(10, CUSTOMER_A, "100.00", TODAY)));
            when(applications.sumAppliedAmountByInvoiceIdIn(anyCollection()))
                    .thenReturn(List.of(new InvoiceAmount(id(10), new BigDecimal("100.00"))));
            when(applications.findPaymentIdsWithLiveApplicationToSourceInvoice(anyCollection()))
                    .thenReturn(List.of(payment.getPaymentId()));

            UnappliedPaymentRow row = list(payment).getItems().getFirst();

            assertThat(row.getSourceInvoiceState()).isEqualTo(SourceInvoiceState.PAID);
            assertThat(row.isPossibleDuplicate()).isFalse();
        }

        @Test
        @DisplayName("an invoice with a balance left is OPEN, not a duplicate, and asks for no applications")
        void openInvoice() {
            ReceivablePayment payment = takenAgainst(1, 10, "100.00", "40.00");
            when(invoices.findAllById(any())).thenReturn(List.of(invoice(10, CUSTOMER_A, "200.00", TODAY)));
            when(applications.sumAppliedAmountByInvoiceIdIn(anyCollection()))
                    .thenReturn(List.of(new InvoiceAmount(id(10), new BigDecimal("60.00"))));

            UnappliedPaymentRow row = list(payment).getItems().getFirst();

            assertThat(row.getSourceInvoiceState()).isEqualTo(SourceInvoiceState.OPEN);
            assertThat(row.isPossibleDuplicate()).isFalse();
            verify(applications, never()).findPaymentIdsWithLiveApplicationToSourceInvoice(anyCollection());
        }

        @Test
        @DisplayName("AC2: an invoice not replicated yet is NOT_FOUND, not a duplicate")
        void notReplicated() {
            ReceivablePayment payment = takenAgainst(1, 10, "100.00", "100.00");

            UnappliedPaymentRow row = list(payment).getItems().getFirst();

            assertThat(row.getSourceInvoiceState()).isEqualTo(SourceInvoiceState.NOT_FOUND);
            assertThat(row.isPossibleDuplicate()).isFalse();
            assertThat(row.getSourceInvoiceNumber()).isNull();
            verify(applications, never()).findPaymentIdsWithLiveApplicationToSourceInvoice(anyCollection());
        }

        @Test
        @DisplayName("AC2: an invoice that is not FINALIZED or POSTED is NOT_ELIGIBLE, not a duplicate, and is not"
                + " priced")
        void notEligible() {
            ReceivablePayment payment = takenAgainst(1, 10, "100.00", "100.00");
            when(invoices.findAllById(any()))
                    .thenReturn(List.of(withStatus(invoice(10, CUSTOMER_A, "100.00", TODAY), "VOIDED")));

            UnappliedPaymentRow row = list(payment).getItems().getFirst();

            assertThat(row.getSourceInvoiceState()).isEqualTo(SourceInvoiceState.NOT_ELIGIBLE);
            assertThat(row.isPossibleDuplicate()).isFalse();
            verify(applications, never()).sumAppliedAmountByInvoiceIdIn(anyCollection());
        }

        @Test
        @DisplayName("AC2: another customer's invoice, or one without a usable party, is OTHER_CUSTOMER, not a"
                + " duplicate")
        void otherCustomer() {
            ReceivablePayment ofB = takenAgainst(1, 10, "100.00", "100.00");
            ReceivablePayment noParty = takenAgainst(2, 11, "100.00", "100.00");
            ExtInvoice partyless = invoice(11, CUSTOMER_A, "100.00", TODAY);
            partyless.setPartyId("not-a-uuid");
            when(invoices.findAllById(any())).thenReturn(List.of(invoice(10, CUSTOMER_B, "100.00", TODAY), partyless));

            List<UnappliedPaymentRow> rows = list(ofB, noParty).getItems();

            assertThat(rows)
                    .extracting(UnappliedPaymentRow::getSourceInvoiceState)
                    .containsExactly(SourceInvoiceState.OTHER_CUSTOMER, SourceInvoiceState.OTHER_CUSTOMER);
            assertThat(rows).noneMatch(UnappliedPaymentRow::isPossibleDuplicate);
            verify(applications, never()).sumAppliedAmountByInvoiceIdIn(anyCollection());
        }

        @Test
        @DisplayName("a payment that names no invoice has no state and is not a duplicate; no invoice is read")
        void noSourceInvoice() {
            UnappliedPaymentRow row =
                    list(payment(1, CUSTOMER_A, "100.00", NOW)).getItems().getFirst();

            assertThat(row.getSourceInvoiceState()).isNull();
            assertThat(row.isPossibleDuplicate()).isFalse();
            verify(invoices, never()).findAllById(any());
            verify(applications, never()).findPaymentIdsWithLiveApplicationToSourceInvoice(anyCollection());
        }

        @Test
        @DisplayName("a page with every case costs one invoice read, one batch of balances, one application query"
                + " (for the PAID rows only) and one outcome query")
        void batched() {
            ReceivablePayment caseG = takenAgainst(1, 10, "100.00", "100.00");
            ReceivablePayment selfPaid = takenAgainst(2, 11, "120.00", "20.00");
            ReceivablePayment open = takenAgainst(3, 12, "50.00", "50.00");
            ReceivablePayment missing = takenAgainst(4, 13, "50.00", "50.00");
            ReceivablePayment ineligible = takenAgainst(5, 14, "50.00", "50.00");
            ReceivablePayment ofB = takenAgainst(6, 15, "50.00", "50.00");
            ReceivablePayment sameInvoiceAsG = takenAgainst(7, 10, "100.00", "100.00");
            ReceivablePayment none = payment(8, CUSTOMER_A, "10.00", NOW);
            when(invoices.findAllById(any()))
                    .thenReturn(List.of(
                            invoice(10, CUSTOMER_A, "100.00", TODAY),
                            invoice(11, CUSTOMER_A, "100.00", TODAY),
                            invoice(12, CUSTOMER_A, "500.00", TODAY),
                            withStatus(invoice(14, CUSTOMER_A, "50.00", TODAY), "DRAFT"),
                            invoice(15, CUSTOMER_B, "50.00", TODAY)));
            when(invoices.findByPartyIdInAndStatusIn(anyCollection(), anyCollection()))
                    .thenReturn(List.of(invoice(12, CUSTOMER_A, "500.00", TODAY)));
            when(applications.sumAppliedAmountByInvoiceIdIn(anyCollection()))
                    .thenReturn(List.of(
                            new InvoiceAmount(id(10), new BigDecimal("100.00")),
                            new InvoiceAmount(id(11), new BigDecimal("100.00"))));
            when(applications.findPaymentIdsWithLiveApplicationToSourceInvoice(anyCollection()))
                    .thenReturn(List.of(selfPaid.getPaymentId()));

            List<UnappliedPaymentRow> rows = list(caseG, selfPaid, open, missing, ineligible, ofB, sameInvoiceAsG, none)
                    .getItems();

            assertThat(rows)
                    .extracting(UnappliedPaymentRow::getSourceInvoiceState)
                    .containsExactly(
                            SourceInvoiceState.PAID,
                            SourceInvoiceState.PAID,
                            SourceInvoiceState.OPEN,
                            SourceInvoiceState.NOT_FOUND,
                            SourceInvoiceState.NOT_ELIGIBLE,
                            SourceInvoiceState.OTHER_CUSTOMER,
                            SourceInvoiceState.PAID,
                            null);
            assertThat(rows)
                    .extracting(UnappliedPaymentRow::isPossibleDuplicate)
                    .containsExactly(true, false, false, false, false, false, true, false);

            verify(invoices, times(1)).findAllById(any());
            verify(invoices, times(1)).findByPartyIdInAndStatusIn(anyCollection(), anyCollection());
            @SuppressWarnings("unchecked")
            ArgumentCaptor<Collection<UUID>> priced = ArgumentCaptor.forClass(Collection.class);
            verify(applications, times(1)).sumAppliedAmountByInvoiceIdIn(priced.capture());
            // The candidates and the source invoices whose balance decides, priced together; never the
            // ineligible or other customer's invoice.
            assertThat(priced.getValue()).containsExactlyInAnyOrder(id(12), id(10), id(11));
            @SuppressWarnings("unchecked")
            ArgumentCaptor<Collection<UUID>> paid = ArgumentCaptor.forClass(Collection.class);
            verify(applications, times(1)).findPaymentIdsWithLiveApplicationToSourceInvoice(paid.capture());
            assertThat(paid.getValue())
                    .containsExactlyInAnyOrder(
                            caseG.getPaymentId(), selfPaid.getPaymentId(), sameInvoiceAsG.getPaymentId());
            verify(events, times(1)).findOutcomesByEventTypeAndDomainKeyIdIn(eq("payment.payment.settled"), any());
            verify(resolver, never()).resolve(eq(DisplayReferenceType.INVOICE), anyCollection());
        }

        @Test
        @DisplayName("an empty page reads no invoice, application or outcome")
        void emptyPage() {
            list();

            verify(invoices, never()).findAllById(any());
            verify(applications, never()).sumAppliedAmountByInvoiceIdIn(anyCollection());
            verify(applications, never()).findPaymentIdsWithLiveApplicationToSourceInvoice(anyCollection());
            verify(events, never()).findOutcomesByEventTypeAndDomainKeyIdIn(any(), any());
        }
    }

    @Nested
    @DisplayName("the automatic-application reason (#2563)")
    class AutoApplicationReason {

        @Test
        @DisplayName("serves the reason on the payment's newest automatic-application record, keyed by its id")
        void newestRecordWins() {
            ReceivablePayment payment = payment(1, CUSTOMER_A, "100.00", NOW);
            when(events.findOutcomesByEventTypeAndDomainKeyIdIn(eq("payment.payment.settled"), anyCollection()))
                    .thenReturn(List.of(
                            outcome(payment, AccountingEventStatus.SKIPPED, "NOT_POSTABLE", NOW.minusSeconds(10)),
                            outcome(
                                    payment,
                                    AccountingEventStatus.SUSPENDED,
                                    "INVOICE_NOT_FOUND",
                                    NOW.minusSeconds(60))));

            UnappliedPaymentRow row = list(payment).getItems().getFirst();

            assertThat(row.getAutoApplicationReason()).isEqualTo("NOT_POSTABLE");
            verify(events)
                    .findOutcomesByEventTypeAndDomainKeyIdIn(
                            "payment.payment.settled",
                            List.of(payment.getPaymentId().toString()));
        }

        @Test
        @DisplayName("a newest record that was processed (a reprocess applied it) leaves no reason")
        void processedRecordHasNoReason() {
            ReceivablePayment payment = payment(1, CUSTOMER_A, "100.00", NOW);
            when(events.findOutcomesByEventTypeAndDomainKeyIdIn(any(), anyCollection()))
                    .thenReturn(List.of(
                            outcome(payment, AccountingEventStatus.PROCESSED, null, NOW.minusSeconds(10)),
                            outcome(
                                    payment,
                                    AccountingEventStatus.FAILED,
                                    "INVOICE_NOT_ELIGIBLE",
                                    NOW.minusSeconds(60))));

            assertThat(list(payment).getItems().getFirst().getAutoApplicationReason())
                    .isNull();
        }

        @Test
        @DisplayName("a record held for another reason (a currency hold) is not an automatic-application record")
        void otherReasonsAreIgnored() {
            ReceivablePayment payment = payment(1, CUSTOMER_A, "100.00", NOW);
            when(events.findOutcomesByEventTypeAndDomainKeyIdIn(any(), anyCollection()))
                    .thenReturn(List.of(
                            outcome(
                                    payment,
                                    AccountingEventStatus.SUSPENDED,
                                    "CURRENCY_NOT_SUPPORTED",
                                    NOW.minusSeconds(10)),
                            outcome(payment, AccountingEventStatus.SUSPENDED, "PERIOD_CLOSED", NOW.minusSeconds(60))));

            assertThat(list(payment).getItems().getFirst().getAutoApplicationReason())
                    .isEqualTo("PERIOD_CLOSED");
        }

        @Test
        @DisplayName("a payment with no record has no reason; each payment gets its own")
        void perPayment() {
            ReceivablePayment held = payment(1, CUSTOMER_A, "100.00", NOW.minusSeconds(10));
            ReceivablePayment unrecorded = payment(2, CUSTOMER_A, "100.00", NOW);
            when(events.findOutcomesByEventTypeAndDomainKeyIdIn(any(), anyCollection()))
                    .thenReturn(List.of(outcome(
                            held, AccountingEventStatus.SUSPENDED, "ACCOUNTING_TIME_ZONE_UNSET", NOW.minusSeconds(5))));

            List<UnappliedPaymentRow> rows = list(held, unrecorded).getItems();

            assertThat(rows)
                    .extracting(UnappliedPaymentRow::getAutoApplicationReason)
                    .containsExactly("ACCOUNTING_TIME_ZONE_UNSET", null);
        }

        @Test
        @DisplayName("the schema's allowable values are exactly what is served: the outcome reasons of automatic"
                + " application, and every source-invoice state")
        void schemaMatchesServedValues() throws NoSuchFieldException {
            Schema reason = UnappliedPaymentRow.class
                    .getDeclaredField("autoApplicationReason")
                    .getAnnotation(Schema.class);
            Schema state = UnappliedPaymentRow.class
                    .getDeclaredField("sourceInvoiceState")
                    .getAnnotation(Schema.class);

            assertThat(reason.allowableValues())
                    .containsExactlyInAnyOrderElementsOf(AutomaticPaymentApplicationService.OUTCOME_REASONS);
            assertThat(state.allowableValues())
                    .containsExactlyInAnyOrderElementsOf(Stream.of(SourceInvoiceState.values())
                            .map(Enum::name)
                            .toList());
            assertThat(reason.nullable()).isTrue();
            assertThat(state.nullable()).isTrue();
        }
    }

    @Nested
    @DisplayName("the CASH walk-in account (#2508)")
    class WalkIn {

        @Test
        @DisplayName("a CASH payment's suggestion offers no leftOver credit; another customer's keeps it; the CASH"
                + " parties are read once per list call")
        void walkInPaymentHasNoLeftOver() {
            ReceivablePayment ofA = payment(1, CUSTOMER_A, "200.00", NOW.minusSeconds(600));
            ReceivablePayment cash = payment(2, CUSTOMER_B, "200.00", NOW.minusSeconds(300));
            ReceivablePayment cashAgain = payment(3, CUSTOMER_B, "60.00", NOW.minusSeconds(200));
            when(payments.findByStatus(eq(ReceivablePaymentStatus.AVAILABLE), any(Pageable.class)))
                    .thenAnswer(call -> new PageImpl<>(List.of(ofA, cash, cashAgain), call.getArgument(1), 3));
            when(payments.totalsByStatus(ReceivablePaymentStatus.AVAILABLE))
                    .thenReturn(new ReceivablePaymentTotals(3, new BigDecimal("460.00")));
            when(invoices.findByPartyIdInAndStatusIn(anyCollection(), anyCollection()))
                    .thenReturn(
                            List.of(invoice(1, CUSTOMER_A, "150.00", TODAY), invoice(2, CUSTOMER_B, "45.00", TODAY)));
            when(parties.findByHouseAccount("CASH_SALE"))
                    .thenReturn(List.of(com.positivity.accounting.internal.entity.ExtCustomerParty.builder()
                            .partyId(CUSTOMER_B)
                            .houseAccount("CASH_SALE")
                            .build()));

            UnappliedPaymentsPage page = service.listUnappliedPayments(null, 0, 25);

            assertThat(page.getItems().get(0).getSuggestion().getLeftOver()).isNotNull();
            assertThat(page.getItems().get(1).getSuggestion().getLeftOver()).isNull();
            assertThat(page.getItems().get(1).getSuggestion().getSuggestedTotal())
                    .isNotNull();
            assertThat(page.getItems().get(2).getSuggestion().getLeftOver()).isNull();
            verify(parties, org.mockito.Mockito.times(1)).findByHouseAccount("CASH_SALE");
        }

        @Test
        @DisplayName("an empty page reads no CASH party")
        void emptyPageSkipsTheLookup() {
            when(payments.findByStatus(eq(ReceivablePaymentStatus.AVAILABLE), any(Pageable.class)))
                    .thenAnswer(call -> new PageImpl<>(List.of(), call.getArgument(1), 0));
            when(payments.totalsByStatus(ReceivablePaymentStatus.AVAILABLE))
                    .thenReturn(new ReceivablePaymentTotals(0, BigDecimal.ZERO));

            service.listUnappliedPayments(null, 0, 25);

            verify(parties, never()).findByHouseAccount(any());
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
