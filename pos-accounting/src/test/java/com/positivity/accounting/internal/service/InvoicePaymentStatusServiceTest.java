package com.positivity.accounting.internal.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.positivity.accounting.internal.config.TestSecurityConfig;
import com.positivity.accounting.internal.dto.InvoiceStatusResponse;
import com.positivity.accounting.internal.dto.PaymentAppliedRequest;
import com.positivity.accounting.internal.entity.CreditMemo;
import com.positivity.accounting.internal.entity.ExtInvoice;
import com.positivity.accounting.internal.entity.InvoiceStatusView;
import com.positivity.accounting.internal.entity.PaymentApplication;
import com.positivity.accounting.internal.entity.PaymentApplicationReversal;
import com.positivity.accounting.internal.entity.ReceivablePayment;
import com.positivity.accounting.internal.enums.CreditMemoStatus;
import com.positivity.accounting.internal.enums.PaymentStatus;
import com.positivity.accounting.internal.repository.ExtInvoiceRepository;
import com.positivity.accounting.internal.repository.InvoiceStatusViewRepository;
import com.positivity.accounting.internal.repository.PaymentAppliedEventRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityNotFoundException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Integration tests for InvoicePaymentStatusService.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Transactional
@Import(TestSecurityConfig.class)
@DisplayName("Phase 3 Integration Tests - Invoice Payment Status Service Wrappers")
class InvoicePaymentStatusServiceTest {

    @Autowired
    private InvoicePaymentStatusServiceImpl paymentStatusService;

    @Autowired
    private PaymentAppliedEventRepository paymentEventRepository;

    @Autowired
    private InvoiceStatusViewRepository statusViewRepository;

    @Autowired
    private ExtInvoiceRepository extInvoiceRepository;

    @Autowired
    private EntityManager entityManager;

    private static final UUID INV_001 = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID INV_002 = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID INV_003 = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID INV_004 = UUID.fromString("00000000-0000-0000-0000-000000000004");
    private static final UUID INV_005 = UUID.fromString("00000000-0000-0000-0000-000000000005");
    private static final UUID INV_006 = UUID.fromString("00000000-0000-0000-0000-000000000006");

    @BeforeEach
    void setUp() {
        paymentEventRepository.deleteAll();
        statusViewRepository.deleteAll();
        extInvoiceRepository.deleteAll();
    }

    @Test
    void testFullPayment() {
        // Arrange
        PaymentAppliedRequest request = new PaymentAppliedRequest();
        request.setInvoiceId(INV_001);
        request.setTransactionReference("TXN-001");
        request.setPaymentAmount(new BigDecimal("100.00"));
        request.setInvoiceTotal(new BigDecimal("100.00"));
        request.setIdempotencyKey("key-001");
        request.setPaymentFailed(false);

        // Act
        InvoiceStatusResponse response = paymentStatusService.processPaymentApplied(request);

        // Assert
        assertNotNull(response);
        assertEquals(INV_001, response.getInvoiceId());
        assertEquals(PaymentStatus.PAID, response.getStatus());
        assertEquals(0, response.getTotalPaid().compareTo(new BigDecimal("100.00")));
        assertEquals(0, response.getRemainingBalance().compareTo(BigDecimal.ZERO));
    }

    @Test
    void testPartialPayment() {
        // Arrange
        PaymentAppliedRequest request = new PaymentAppliedRequest();
        request.setInvoiceId(INV_002);
        request.setTransactionReference("TXN-002");
        request.setPaymentAmount(new BigDecimal("50.00"));
        request.setInvoiceTotal(new BigDecimal("100.00"));
        request.setIdempotencyKey("key-002");
        request.setPaymentFailed(false);

        // Act
        InvoiceStatusResponse response = paymentStatusService.processPaymentApplied(request);

        // Assert
        assertNotNull(response);
        assertEquals(PaymentStatus.PARTIALLY_PAID, response.getStatus());
        assertEquals(0, response.getTotalPaid().compareTo(new BigDecimal("50.00")));
        assertEquals(0, response.getRemainingBalance().compareTo(new BigDecimal("50.00")));
    }

    @Test
    void testMultiplePartialPayments() {
        // Arrange - First payment
        PaymentAppliedRequest request1 = new PaymentAppliedRequest();
        request1.setInvoiceId(INV_003);
        request1.setTransactionReference("TXN-003-1");
        request1.setPaymentAmount(new BigDecimal("30.00"));
        request1.setInvoiceTotal(new BigDecimal("100.00"));
        request1.setIdempotencyKey("key-003-1");

        // Act - First payment
        InvoiceStatusResponse response1 = paymentStatusService.processPaymentApplied(request1);
        assertEquals(PaymentStatus.PARTIALLY_PAID, response1.getStatus());

        // Arrange - Second payment
        PaymentAppliedRequest request2 = new PaymentAppliedRequest();
        request2.setInvoiceId(INV_003);
        request2.setTransactionReference("TXN-003-2");
        request2.setPaymentAmount(new BigDecimal("70.00"));
        request2.setInvoiceTotal(new BigDecimal("100.00"));
        request2.setIdempotencyKey("key-003-2");

        // Act - Second payment
        InvoiceStatusResponse response2 = paymentStatusService.processPaymentApplied(request2);

        // Assert
        assertEquals(PaymentStatus.PAID, response2.getStatus());
        assertEquals(0, response2.getTotalPaid().compareTo(new BigDecimal("100.00")));
    }

    @Test
    void testFailedPayment() {
        // Arrange
        PaymentAppliedRequest request = new PaymentAppliedRequest();
        request.setInvoiceId(INV_004);
        request.setTransactionReference("TXN-004");
        request.setPaymentAmount(new BigDecimal("100.00"));
        request.setInvoiceTotal(new BigDecimal("100.00"));
        request.setIdempotencyKey("key-004");
        request.setPaymentFailed(true);

        // Act
        paymentStatusService.processPaymentApplied(request);

        // Assert - Failed payments don't count toward total
        InvoiceStatusView statusView =
                statusViewRepository.findByInvoiceId(INV_004).orElseThrow();
        assertEquals(PaymentStatus.UNPAID, statusView.getCurrentStatus());
        assertEquals(0, statusView.getTotalPaid().compareTo(BigDecimal.ZERO));
    }

    @Test
    void testIdempotency() {
        // Arrange
        PaymentAppliedRequest request = new PaymentAppliedRequest();
        request.setInvoiceId(INV_005);
        request.setTransactionReference("TXN-005");
        request.setPaymentAmount(new BigDecimal("100.00"));
        request.setInvoiceTotal(new BigDecimal("100.00"));
        request.setIdempotencyKey("key-005");

        // Act - Process same payment twice
        InvoiceStatusResponse response1 = paymentStatusService.processPaymentApplied(request);
        InvoiceStatusResponse response2 = paymentStatusService.processPaymentApplied(request);

        // Assert - Only one payment event should be created
        long eventCount = paymentEventRepository
                .findByInvoiceIdOrderByTimestampDesc(INV_005)
                .size();
        assertEquals(1, eventCount);

        // Both responses should be identical
        assertEquals(response1.getStatus(), response2.getStatus());
        assertEquals(response1.getTotalPaid(), response2.getTotalPaid());
    }

    @Test
    void testGetInvoiceStatus() {
        // Arrange - Create an invoice with payment
        PaymentAppliedRequest request = new PaymentAppliedRequest();
        request.setInvoiceId(INV_006);
        request.setTransactionReference("TXN-006");
        request.setPaymentAmount(new BigDecimal("75.00"));
        request.setInvoiceTotal(new BigDecimal("100.00"));
        request.setIdempotencyKey("key-006");

        paymentStatusService.processPaymentApplied(request);

        // Act
        InvoiceStatusResponse response1 = paymentStatusService.getInvoiceStatus(INV_006);

        // Assert
        assertNotNull(response1);
        assertEquals(INV_006, response1.getInvoiceId());
        assertEquals(PaymentStatus.PARTIALLY_PAID, response1.getStatus());
        assertEquals(0, response1.getTotalPaid().compareTo(new BigDecimal("75.00")));
    }

    @Test
    @DisplayName("Invoice known to accounting but with no payment history reads as UNPAID, not 404 (#1634)")
    void testGetInvoiceStatusFallsBackToReplicaWhenNoPaymentsApplied() {
        // Arrange - invoice exists only in the ext_invoice replica (fed by invoice.events.v1)
        UUID invoiceId = UUID.fromString("01a0482d-d3d5-7067-9cf0-71dfe9b9c5b3");
        extInvoiceRepository.save(ExtInvoice.builder()
                .invoiceId(invoiceId)
                .workorderId(UUID.fromString("00000000-0000-0000-0000-0000000000aa"))
                .status("FINALIZED")
                .total(new BigDecimal("250.00"))
                .aggregateVersion(1L)
                .updatedAt(Instant.parse("2026-08-01T00:00:00Z"))
                .build());

        // Act
        InvoiceStatusResponse response = paymentStatusService.getInvoiceStatus(invoiceId);

        // Assert - a known invoice with no payments is an explicit UNPAID state
        assertNotNull(response);
        assertEquals(invoiceId, response.getInvoiceId());
        assertEquals(PaymentStatus.UNPAID, response.getStatus());
        assertEquals(0, response.getTotalPaid().compareTo(BigDecimal.ZERO));
        assertEquals(0, response.getInvoiceTotal().compareTo(new BigDecimal("250.00")));
        assertEquals(0, response.getRemainingBalance().compareTo(new BigDecimal("250.00")));
        assertNull(response.getLatestTransactionReference());
    }

    @Test
    @DisplayName("Invoice unknown to accounting (no replica record) still raises EntityNotFoundException")
    void testGetInvoiceStatusUnknownInvoiceStillNotFound() {
        UUID unknownInvoiceId = UUID.fromString("01a0ffff-ffff-7fff-8fff-ffffffffffff");

        assertThrows(EntityNotFoundException.class, () -> paymentStatusService.getInvoiceStatus(unknownInvoiceId));
    }

    @Test
    @DisplayName("Replica fallback derives PARTIALLY_PAID from accounting-owned payment applications")
    void testReplicaFallbackDerivesPartiallyPaidFromPaymentApplications() {
        UUID invoiceId = UUID.fromString("01a04001-0000-7000-8000-000000000001");
        persistReplicaInvoice(invoiceId, "FINALIZED", new BigDecimal("200.00"));
        persistPaymentApplication(invoiceId, new BigDecimal("80.00"));

        InvoiceStatusResponse response = paymentStatusService.getInvoiceStatus(invoiceId);

        assertNotNull(response);
        assertEquals(invoiceId, response.getInvoiceId());
        assertEquals(PaymentStatus.PARTIALLY_PAID, response.getStatus());
        assertEquals(0, response.getTotalPaid().compareTo(new BigDecimal("80.00")));
        assertEquals(0, response.getInvoiceTotal().compareTo(new BigDecimal("200.00")));
        assertEquals(0, response.getRemainingBalance().compareTo(new BigDecimal("120.00")));
        assertNull(response.getLatestTransactionReference());
    }

    @Test
    @DisplayName("Replica fallback derives PAID when payment applications cover the full total")
    void testReplicaFallbackDerivesPaidWhenApplicationsCoverTotal() {
        UUID invoiceId = UUID.fromString("01a04002-0000-7000-8000-000000000002");
        persistReplicaInvoice(invoiceId, "FINALIZED", new BigDecimal("200.00"));
        persistPaymentApplication(invoiceId, new BigDecimal("120.00"));
        persistPaymentApplication(invoiceId, new BigDecimal("80.00"));

        InvoiceStatusResponse response = paymentStatusService.getInvoiceStatus(invoiceId);

        assertNotNull(response);
        assertEquals(PaymentStatus.PAID, response.getStatus());
        assertEquals(0, response.getTotalPaid().compareTo(new BigDecimal("200.00")));
        assertEquals(0, response.getRemainingBalance().compareTo(BigDecimal.ZERO));
    }

    @Test
    @DisplayName("Replica fallback reports a zero-total FINALIZED invoice as PAID (nothing was ever due)")
    void testReplicaFallbackZeroTotalFinalizedInvoiceIsPaid() {
        UUID invoiceId = UUID.fromString("01a04003-0000-7000-8000-000000000003");
        persistReplicaInvoice(invoiceId, "FINALIZED", new BigDecimal("0.00"));

        InvoiceStatusResponse response = paymentStatusService.getInvoiceStatus(invoiceId);

        // Canonical calculator answer: balanceDue 0 -> PAID_IN_FULL -> PAID with nothing settled.
        assertNotNull(response);
        assertEquals(PaymentStatus.PAID, response.getStatus());
        assertEquals(0, response.getTotalPaid().compareTo(BigDecimal.ZERO));
        assertEquals(0, response.getInvoiceTotal().compareTo(BigDecimal.ZERO));
        assertEquals(0, response.getRemainingBalance().compareTo(BigDecimal.ZERO));
    }

    @Test
    @DisplayName("Replica fallback clamps settled to zero when reversals exceed applications (balanceDue > total)")
    void testReplicaFallbackClampsSettledToZeroWhenReversalsExceedApplications() {
        UUID invoiceId = UUID.fromString("01a04004-0000-7000-8000-000000000004");
        persistReplicaInvoice(invoiceId, "FINALIZED", new BigDecimal("200.00"));
        PaymentApplication application = persistPaymentApplication(invoiceId, new BigDecimal("50.00"));
        // Reversal larger than the applied amount pushes balanceDue (250.00) above the total.
        persistReversal(application, new BigDecimal("100.00"));

        InvoiceStatusResponse response = paymentStatusService.getInvoiceStatus(invoiceId);

        // settled = (total - balanceDue).max(ZERO) -> clamped to 0, and the AR status is OPEN -> UNPAID.
        assertNotNull(response);
        assertEquals(PaymentStatus.UNPAID, response.getStatus());
        assertEquals(0, response.getTotalPaid().compareTo(BigDecimal.ZERO));
        assertEquals(0, response.getRemainingBalance().compareTo(new BigDecimal("200.00")));
    }

    @Test
    @DisplayName("Replica fallback reports a credit-memo-settled invoice as PAID (settled includes posted credits)")
    void testReplicaFallbackCreditMemoSettledInvoiceIsPaid() {
        UUID invoiceId = UUID.fromString("01a04005-0000-7000-8000-000000000005");
        persistReplicaInvoice(invoiceId, "FINALIZED", new BigDecimal("100.00"));
        persistPostedCreditMemo(invoiceId, new BigDecimal("100.00"));

        InvoiceStatusResponse response = paymentStatusService.getInvoiceStatus(invoiceId);

        // No cash was ever applied: totalPaid here is the settled amount including posted credit memos.
        assertNotNull(response);
        assertEquals(PaymentStatus.PAID, response.getStatus());
        assertEquals(0, response.getTotalPaid().compareTo(new BigDecimal("100.00")));
        assertEquals(0, response.getRemainingBalance().compareTo(BigDecimal.ZERO));
    }

    @Test
    @DisplayName("Replica fallback: DRAFT invoice with null total reads as UNPAID with zeroed amounts, not 500 (#1641)")
    void testReplicaFallbackDraftInvoiceWithNullTotal() {
        UUID invoiceId = UUID.fromString("01a04006-0000-7000-8000-000000000006");
        persistReplicaInvoice(invoiceId, "DRAFT", null);

        InvoiceStatusResponse response = paymentStatusService.getInvoiceStatus(invoiceId);

        // Non-AR-eligible lifecycle: no calculator math, null total coerced to ZERO.
        assertNotNull(response);
        assertEquals(invoiceId, response.getInvoiceId());
        assertEquals(PaymentStatus.UNPAID, response.getStatus());
        assertEquals(0, response.getTotalPaid().compareTo(BigDecimal.ZERO));
        assertEquals(0, response.getInvoiceTotal().compareTo(BigDecimal.ZERO));
        assertEquals(0, response.getRemainingBalance().compareTo(BigDecimal.ZERO));
        assertNull(response.getLatestTransactionReference());
    }

    @Test
    @DisplayName("Replica fallback: ERROR-status invoice reads as UNPAID with its full total remaining")
    void testReplicaFallbackErrorStatusInvoiceIsUnpaidWithTotalRemaining() {
        UUID invoiceId = UUID.fromString("01a04007-0000-7000-8000-000000000007");
        persistReplicaInvoice(invoiceId, "ERROR", new BigDecimal("150.00"));

        InvoiceStatusResponse response = paymentStatusService.getInvoiceStatus(invoiceId);

        // ERROR is not AR-eligible: explicit UNPAID known-absence, payments cannot exist for it.
        assertNotNull(response);
        assertEquals(PaymentStatus.UNPAID, response.getStatus());
        assertEquals(0, response.getTotalPaid().compareTo(BigDecimal.ZERO));
        assertEquals(0, response.getInvoiceTotal().compareTo(new BigDecimal("150.00")));
        assertEquals(0, response.getRemainingBalance().compareTo(new BigDecimal("150.00")));
    }

    // ------------------------------------------------------------------
    // Fixtures for the ext_invoice replica fallback (no invoice_status_views row)
    // ------------------------------------------------------------------

    private static final Instant FIXTURE_AT = Instant.parse("2026-08-01T00:00:00Z");

    private void persistReplicaInvoice(UUID invoiceId, String status, BigDecimal total) {
        extInvoiceRepository.save(ExtInvoice.builder()
                .invoiceId(invoiceId)
                .workorderId(UUID.randomUUID())
                .status(status)
                .total(total)
                .aggregateVersion(1L)
                .updatedAt(FIXTURE_AT)
                .build());
    }

    /**
     * Persists a cleared payment and one application of it against {@code invoiceId}, mirroring the
     * fixture in {@code PaymentApplicationReversalWindowPersistenceTest}. Uses the EntityManager so
     * the immutable {@link PaymentApplication} is unambiguously INSERTed and flushed before the
     * service queries run.
     */
    private PaymentApplication persistPaymentApplication(UUID invoiceId, BigDecimal amount) {
        ReceivablePayment payment = new ReceivablePayment();
        payment.setCustomerId(UUID.randomUUID());
        payment.setCurrency("USD");
        payment.setTotalAmount(amount);
        payment.setUnappliedAmount(BigDecimal.ZERO);
        payment.setStatus(ReceivablePayment.ReceivablePaymentStatus.FULLY_APPLIED);
        payment.setClearedAt(FIXTURE_AT);
        payment.setSourceEventId(UUID.randomUUID());
        payment.setCreatedAt(FIXTURE_AT);
        payment.setCreatedBy("testuser");
        entityManager.persist(payment);

        PaymentApplication application = new PaymentApplication();
        application.setPayment(payment);
        application.setInvoiceId(invoiceId);
        application.setCustomerId(payment.getCustomerId());
        application.setCurrency("USD");
        application.setAppliedAmount(amount);
        application.setApplicationTimestamp(FIXTURE_AT);
        application.setApplicationRequestId(UUID.randomUUID().toString());
        application.setCreatedAt(FIXTURE_AT);
        application.setCreatedBy("testuser");
        entityManager.persist(application);

        entityManager.flush();
        return application;
    }

    private void persistReversal(PaymentApplication application, BigDecimal amount) {
        PaymentApplicationReversal reversal = new PaymentApplicationReversal();
        reversal.setOriginalPaymentApplication(application);
        reversal.setAmount(amount);
        reversal.setReason("Test reversal exceeding the applied amount");
        reversal.setReversedAt(FIXTURE_AT);
        reversal.setReversedBy("testuser");
        entityManager.persist(reversal);
        entityManager.flush();
    }

    private void persistPostedCreditMemo(UUID invoiceId, BigDecimal creditAmount) {
        CreditMemo memo = new CreditMemo();
        memo.setOriginalInvoiceId(invoiceId);
        memo.setCustomerId(UUID.randomUUID());
        memo.setCreditAmount(creditAmount);
        memo.setTaxAmountReversed(BigDecimal.ZERO);
        memo.setReasonCode("RETURNED_GOODS");
        memo.setStatus(CreditMemoStatus.POSTED);
        memo.setCreatedByUserId("testuser");
        memo.setCurrency("USD");
        entityManager.persist(memo);
        entityManager.flush();
    }
}
