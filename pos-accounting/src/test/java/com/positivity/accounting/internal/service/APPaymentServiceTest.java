package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.dto.APPaymentResponse;
import com.positivity.accounting.internal.dto.ExecuteAPPaymentRequest;
import com.positivity.accounting.internal.dto.VendorBillSummaryResponse;
import com.positivity.accounting.internal.entity.APPayment;
import com.positivity.accounting.internal.entity.JournalEntry;
import com.positivity.accounting.internal.entity.VendorBill;
import com.positivity.accounting.internal.enums.APPaymentStatus;
import com.positivity.accounting.internal.enums.PaymentMethod;
import com.positivity.accounting.internal.enums.VendorBillApproverKind;
import com.positivity.accounting.internal.enums.VendorBillStatus;
import com.positivity.accounting.internal.exception.IdempotencyConflictException;
import com.positivity.accounting.internal.exception.InvalidBillAllocationException;
import com.positivity.accounting.internal.exception.PaymentGatewayException;
import com.positivity.accounting.internal.exception.VendorBillException;
import com.positivity.accounting.internal.payment.GatewayPaymentResponse;
import com.positivity.accounting.internal.payment.PaymentGatewayProvider;
import com.positivity.accounting.internal.repository.APPaymentAllocationRepository;
import com.positivity.accounting.internal.repository.APPaymentRepository;
import com.positivity.accounting.internal.repository.JournalEntryRepository;
import com.positivity.accounting.internal.repository.VendorBillRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/**
 * Unit tests for APPaymentServiceImpl.
 * Covers executePayment, getPaymentById, getPaymentByRef, listEligibleBills,
 * acknowledgeGLPosted, recordGLPostFailure.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("APPaymentService Unit Tests")
class APPaymentServiceTest {

    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);
    private static final UUID TEST_PAYMENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000099");

    @Spy
    Clock clock = TEST_CLOCK;

    @Mock
    private APPaymentRepository paymentRepository;

    @Mock
    private APPaymentAllocationRepository allocationRepository;

    @Mock
    private VendorBillRepository billRepository;

    @Mock
    private JournalEntryRepository journalEntryRepository;

    @Mock
    private PaymentGatewayProvider paymentGateway;

    @Mock
    private OutboxService outboxService;

    @Mock
    private APPaymentFailurePersistenceService paymentFailurePersistenceService;

    @Mock
    private VendorBillPayGuard payGuard;

    @InjectMocks
    private APPaymentServiceImpl service;

    private UUID testVendorId;
    private String testPaymentRef;

    @BeforeEach
    void setUp() {
        testVendorId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        testPaymentRef = "PAY-REF-001";

        // Default:
        // allocationRepository.findByPayment_PaymentIdOrderByAllocationSequenceAsc
        // returns empty
        when(allocationRepository.findByPayment_PaymentIdOrderByAllocationSequenceAsc(any(UUID.class)))
                .thenReturn(List.of());
        // Default: allocationRepository.saveAll returns empty list
        when(allocationRepository.saveAll(any())).thenReturn(List.of());
        // Default: the locked APPROVED bills of the vendor are none (#2509 review, A3)
        when(billRepository.lockByVendorIdAndStatus(any(UUID.class), any(VendorBillStatus.class)))
                .thenReturn(List.of());
    }

    // ========================================
    // executePayment Tests
    // ========================================

    @Test
    @DisplayName("executePayment should succeed when gateway returns SUCCEEDED status")
    void executePayment_Success_GatewaySucceeded() {
        ExecuteAPPaymentRequest request =
                buildRequest(testPaymentRef, testVendorId, new BigDecimal("1500.00"), PaymentMethod.ACH);

        // No existing payment
        when(paymentRepository.findByPaymentRef(testPaymentRef)).thenReturn(Optional.empty());

        // Save payment with paymentId assigned on first call
        when(paymentRepository.save(any(APPayment.class))).thenAnswer(inv -> {
            APPayment p = inv.getArgument(0);
            if (p.getPaymentId() == null) {
                p.setPaymentId(TEST_PAYMENT_ID);
            }
            return p;
        });

        GatewayPaymentResponse gatewayResponse = GatewayPaymentResponse.builder()
                .transactionId("txn-001")
                .status(PaymentGatewayProvider.GatewayPaymentStatus.SUCCEEDED)
                .rawResponse("{\"status\":\"succeeded\"}")
                .build();
        when(paymentGateway.executePayment(any())).thenReturn(gatewayResponse);

        APPaymentResponse result = service.executePayment(request, "test-user");

        assertThat(result).isNotNull();
        assertThat(result.getPaymentId()).isEqualTo(TEST_PAYMENT_ID);
        assertThat(result.getPaymentRef()).isEqualTo(testPaymentRef);
        assertThat(result.getVendorId()).isEqualTo(testVendorId);
        verify(paymentGateway).executePayment(any());
        verify(outboxService).saveToOutbox(any(), anyString(), any(), anyString(), any());
    }

    @Test
    @DisplayName("executePayment with explicit payment source")
    void executePayment_WithExplicitPaymentSource() {
        ExecuteAPPaymentRequest request =
                buildRequest(testPaymentRef, testVendorId, new BigDecimal("1000.00"), PaymentMethod.WIRE);
        request.setPaymentSource("bank-account-123");

        when(paymentRepository.findByPaymentRef(testPaymentRef)).thenReturn(Optional.empty());
        when(paymentRepository.save(any(APPayment.class))).thenAnswer(inv -> {
            APPayment p = inv.getArgument(0);
            if (p.getPaymentId() == null) p.setPaymentId(TEST_PAYMENT_ID);
            return p;
        });

        GatewayPaymentResponse response = GatewayPaymentResponse.builder()
                .transactionId("txn-002")
                .status(PaymentGatewayProvider.GatewayPaymentStatus.AUTHORIZED)
                .build();
        when(paymentGateway.executePayment(any())).thenReturn(response);

        APPaymentResponse result = service.executePayment(request, "test-user");

        assertThat(result).isNotNull();
        assertThat(result.getPaymentId()).isEqualTo(TEST_PAYMENT_ID);
    }

    @Test
    @DisplayName("executePayment should return existing payment for idempotent duplicate with matching fields")
    void executePayment_Idempotent_ReturnExisting() {
        ExecuteAPPaymentRequest request =
                buildRequest(testPaymentRef, testVendorId, new BigDecimal("1500.00"), PaymentMethod.ACH);

        APPayment existingPayment = buildExistingPayment(
                TEST_PAYMENT_ID, testPaymentRef, testVendorId, new BigDecimal("1500.00"), PaymentMethod.ACH);
        when(paymentRepository.findByPaymentRef(testPaymentRef)).thenReturn(Optional.of(existingPayment));
        when(allocationRepository.findByPayment_PaymentIdOrderByAllocationSequenceAsc(TEST_PAYMENT_ID))
                .thenReturn(List.of());

        APPaymentResponse result = service.executePayment(request, "test-user");

        assertThat(result).isNotNull();
        assertThat(result.getPaymentId()).isEqualTo(TEST_PAYMENT_ID);
        // Gateway should NOT have been called for idempotent replays
        verify(paymentGateway, never()).executePayment(any());
    }

    @Test
    @DisplayName("executePayment should throw IdempotencyConflictException when duplicate ref has different fields")
    void executePayment_IdempotencyConflict() {
        ExecuteAPPaymentRequest request =
                buildRequest(testPaymentRef, testVendorId, new BigDecimal("1500.00"), PaymentMethod.ACH);

        // Existing payment with different amount
        APPayment existingPayment = buildExistingPayment(
                TEST_PAYMENT_ID,
                testPaymentRef,
                testVendorId,
                new BigDecimal("999.00"), // different amount
                PaymentMethod.ACH);
        when(paymentRepository.findByPaymentRef(testPaymentRef)).thenReturn(Optional.of(existingPayment));

        assertThatThrownBy(() -> service.executePayment(request, "test-user"))
                .isInstanceOf(IdempotencyConflictException.class)
                .hasMessageContaining("Conflicting payload");
    }

    @Test
    @DisplayName("executePayment should throw PaymentGatewayException when gateway returns DECLINED")
    void executePayment_GatewayDeclined() {
        ExecuteAPPaymentRequest request =
                buildRequest(testPaymentRef, testVendorId, new BigDecimal("1500.00"), PaymentMethod.ACH);

        when(paymentRepository.findByPaymentRef(testPaymentRef)).thenReturn(Optional.empty());
        when(paymentRepository.save(any(APPayment.class))).thenAnswer(inv -> {
            APPayment p = inv.getArgument(0);
            if (p.getPaymentId() == null) p.setPaymentId(TEST_PAYMENT_ID);
            return p;
        });

        GatewayPaymentResponse declinedResponse = GatewayPaymentResponse.builder()
                .transactionId("txn-declined")
                .status(PaymentGatewayProvider.GatewayPaymentStatus.DECLINED)
                .failureReason("Insufficient funds")
                .build();
        when(paymentGateway.executePayment(any())).thenReturn(declinedResponse);

        assertThatThrownBy(() -> service.executePayment(request, "test-user"))
                .isInstanceOf(PaymentGatewayException.class);

        // persistGatewayFailure should be called due to the exception being re-thrown
        // via the caught exception
        verify(paymentFailurePersistenceService).persistGatewayFailure(any(UUID.class), any());
    }

    @Test
    @DisplayName("executePayment should throw PaymentGatewayException when gateway communication fails")
    void executePayment_GatewayCommunicationFailure() {
        ExecuteAPPaymentRequest request =
                buildRequest(testPaymentRef, testVendorId, new BigDecimal("1500.00"), PaymentMethod.ACH);

        when(paymentRepository.findByPaymentRef(testPaymentRef)).thenReturn(Optional.empty());
        when(paymentRepository.save(any(APPayment.class))).thenAnswer(inv -> {
            APPayment p = inv.getArgument(0);
            if (p.getPaymentId() == null) p.setPaymentId(TEST_PAYMENT_ID);
            return p;
        });

        when(paymentGateway.executePayment(any())).thenThrow(new RuntimeException("Connection timeout"));

        assertThatThrownBy(() -> service.executePayment(request, "test-user"))
                .isInstanceOf(PaymentGatewayException.class)
                .hasMessageContaining("Payment gateway communication failure");

        verify(paymentFailurePersistenceService).persistGatewayFailure(any(UUID.class), any());
    }

    // ========================================
    // getPaymentById Tests
    // ========================================

    // ========================================
    // The pre-gateway block and the pay guard (CAP:550 S13, #2510)
    // ========================================

    private VendorBill approvedBill(String number, String total, String approvedBy) {
        VendorBill bill = new VendorBill(UUID.fromString("0199a1b2-c3d4-7e5f-8a9b-0c1d2e3f4b0" + number.length()));
        bill.setVendorId(testVendorId);
        bill.setBillNumber(number);
        bill.setTotalAmount(new BigDecimal(total));
        bill.setStatus(VendorBillStatus.APPROVED);
        bill.setApprovedBy(approvedBy);
        bill.setApprovedByKind(VendorBillApproverKind.PERSON);
        return bill;
    }

    private static VendorBillException selfApproved(String billNumber) {
        return new VendorBillException(
                VendorBillException.Code.AP_PAYMENT_SELF_APPROVED_BILL,
                "You approved a bill this payment would pay",
                List.of(new VendorBillException.FieldError("selfApprovedBillNumbers", billNumber)),
                null);
    }

    @Test
    @DisplayName("AC6 (S13): explicit allocations: the plan's locked bills go to the pay guard before any payment row"
            + " is saved and before the gateway; a refusal persists nothing and calls no gateway")
    void payGuardRefusesExplicitPlanBeforeTheGateway() {
        VendorBill bill = approvedBill("INV-B", "400.00", "ana");
        ExecuteAPPaymentRequest request =
                buildRequest(testPaymentRef, testVendorId, new BigDecimal("400.00"), PaymentMethod.ACH);
        request.setAllocations(List.of(
                new ExecuteAPPaymentRequest.AllocationLineRequest(bill.getVendorBillId(), new BigDecimal("400.00"))));
        when(paymentRepository.findByPaymentRef(testPaymentRef)).thenReturn(Optional.empty());
        when(billRepository.lockByVendorBillIdIn(any())).thenReturn(List.of(bill));
        org.mockito.Mockito.doThrow(selfApproved("INV-B"))
                .when(payGuard)
                .check(eq(List.of(bill)), eq("ana"), eq(testPaymentRef));

        assertThatThrownBy(() -> service.executePayment(request, "ana"))
                .isInstanceOf(VendorBillException.class)
                .extracting(e -> ((VendorBillException) e).getCode())
                .isEqualTo(VendorBillException.Code.AP_PAYMENT_SELF_APPROVED_BILL);
        verify(payGuard).check(List.of(bill), "ana", testPaymentRef);
        verify(paymentRepository, never()).save(any(APPayment.class));
        verify(paymentGateway, never()).executePayment(any());
        verify(allocationRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("AC6 (S13): oldest-due-first allocation hands the guard every bill it reaches; never skips one")
    void payGuardSeesTheAutomaticPlan() {
        VendorBill older = approvedBill("INV-OLD", "100.00", "ana");
        older.setDueDate(java.time.LocalDateTime.of(2026, 10, 1, 0, 0));
        VendorBill newer = approvedBill("INV-NEWER", "300.00", "bob");
        newer.setDueDate(java.time.LocalDateTime.of(2026, 10, 20, 0, 0));
        ExecuteAPPaymentRequest request =
                buildRequest(testPaymentRef, testVendorId, new BigDecimal("150.00"), PaymentMethod.ACH);
        when(paymentRepository.findByPaymentRef(testPaymentRef)).thenReturn(Optional.empty());
        when(billRepository.lockByVendorIdAndStatus(testVendorId, VendorBillStatus.APPROVED))
                .thenReturn(List.of(newer, older));
        when(billRepository.findById(older.getVendorBillId())).thenReturn(Optional.of(older));
        when(billRepository.findById(newer.getVendorBillId())).thenReturn(Optional.of(newer));
        when(allocationRepository.sumAllocatedAmountByVendorBillId(any())).thenReturn(BigDecimal.ZERO);
        org.mockito.Mockito.doThrow(selfApproved("INV-OLD")).when(payGuard).check(any(), eq("ana"), any());

        assertThatThrownBy(() -> service.executePayment(request, "ana")).isInstanceOf(VendorBillException.class);
        verify(payGuard).check(List.of(older, newer), "ana", testPaymentRef);
        verify(paymentRepository, never()).save(any(APPayment.class));
        verify(paymentGateway, never()).executePayment(any());
    }

    @Test
    @DisplayName("S13 review: an explicit 0.00 line pays nothing, so its bill never reaches the pay guard")
    void zeroAllocationIsNotPaid() {
        VendorBill bill = approvedBill("INV-Z", "400.00", "ana");
        ExecuteAPPaymentRequest request =
                buildRequest(testPaymentRef, testVendorId, new BigDecimal("400.00"), PaymentMethod.ACH);
        request.setAllocations(
                List.of(new ExecuteAPPaymentRequest.AllocationLineRequest(bill.getVendorBillId(), BigDecimal.ZERO)));
        when(paymentRepository.findByPaymentRef(testPaymentRef)).thenReturn(Optional.empty());
        when(billRepository.lockByVendorBillIdIn(any())).thenReturn(List.of(bill));
        when(paymentRepository.save(any(APPayment.class))).thenAnswer(inv -> inv.getArgument(0));
        // The guard passes; the payment then reaches the gateway (stubbed to fail here, past the guard's point).
        when(paymentGateway.executePayment(any())).thenThrow(new RuntimeException("gateway down"));

        assertThatThrownBy(() -> service.executePayment(request, "ana")).isInstanceOf(PaymentGatewayException.class);

        verify(payGuard).check(List.of(), "ana", testPaymentRef);
    }

    @Test
    @DisplayName("S13: an allocation to an unapproved bill is refused before the payment row and the gateway")
    void invalidPlanRefusedBeforeTheGateway() {
        VendorBill bill = approvedBill("INV-C", "400.00", "bob");
        bill.setStatus(VendorBillStatus.AWAITING_APPROVAL);
        ExecuteAPPaymentRequest request =
                buildRequest(testPaymentRef, testVendorId, new BigDecimal("400.00"), PaymentMethod.ACH);
        request.setAllocations(List.of(
                new ExecuteAPPaymentRequest.AllocationLineRequest(bill.getVendorBillId(), new BigDecimal("400.00"))));
        when(paymentRepository.findByPaymentRef(testPaymentRef)).thenReturn(Optional.empty());
        when(billRepository.lockByVendorBillIdIn(any())).thenReturn(List.of(bill));

        assertThatThrownBy(() -> service.executePayment(request, "ana"))
                .isInstanceOf(InvalidBillAllocationException.class);
        verify(paymentRepository, never()).save(any(APPayment.class));
        verify(paymentGateway, never()).executePayment(any());
    }

    @Test
    @DisplayName("getPaymentById should return response when payment found")
    void getPaymentById_Found() {
        APPayment payment = buildExistingPayment(
                TEST_PAYMENT_ID, testPaymentRef, testVendorId, new BigDecimal("1500.00"), PaymentMethod.ACH);
        when(paymentRepository.findById(TEST_PAYMENT_ID)).thenReturn(Optional.of(payment));

        Optional<APPaymentResponse> result = service.getPaymentById(TEST_PAYMENT_ID);

        assertThat(result).isPresent();
        assertThat(result.get().getPaymentId()).isEqualTo(TEST_PAYMENT_ID);
        assertThat(result.get().getPaymentRef()).isEqualTo(testPaymentRef);
    }

    @Test
    @DisplayName("getPaymentById should return empty Optional when payment not found")
    void getPaymentById_NotFound() {
        when(paymentRepository.findById(TEST_PAYMENT_ID)).thenReturn(Optional.empty());

        Optional<APPaymentResponse> result = service.getPaymentById(TEST_PAYMENT_ID);

        assertThat(result).isEmpty();
    }

    // ========================================
    // getPaymentByRef Tests
    // ========================================

    @Test
    @DisplayName("getPaymentByRef should return response when payment found")
    void getPaymentByRef_Found() {
        APPayment payment = buildExistingPayment(
                TEST_PAYMENT_ID, testPaymentRef, testVendorId, new BigDecimal("1500.00"), PaymentMethod.ACH);
        when(paymentRepository.findByPaymentRef(testPaymentRef)).thenReturn(Optional.of(payment));

        Optional<APPaymentResponse> result = service.getPaymentByRef(testPaymentRef);

        assertThat(result).isPresent();
        assertThat(result.get().getPaymentRef()).isEqualTo(testPaymentRef);
    }

    @Test
    @DisplayName("getPaymentByRef should return empty Optional when payment not found")
    void getPaymentByRef_NotFound() {
        when(paymentRepository.findByPaymentRef(testPaymentRef)).thenReturn(Optional.empty());

        Optional<APPaymentResponse> result = service.getPaymentByRef(testPaymentRef);

        assertThat(result).isEmpty();
    }

    // ========================================
    // listEligibleBills Tests
    // ========================================

    @Test
    @DisplayName("listEligibleBills should return empty list when no eligible bills exist")
    void listEligibleBills_Empty() {
        when(billRepository.findByVendorIdAndStatusAndOpenAmountGreaterThan(
                        eq(testVendorId), eq(VendorBillStatus.APPROVED), eq(BigDecimal.ZERO), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        Page<VendorBillSummaryResponse> result = service.listEligibleBills(testVendorId, PageRequest.of(0, 20));

        assertThat(result.getContent()).isEmpty();
    }

    @Test
    @DisplayName("listEligibleBills should return bills sorted by due date")
    void listEligibleBills_WithBills() {
        com.positivity.accounting.internal.entity.VendorBill bill1 =
                new com.positivity.accounting.internal.entity.VendorBill();
        bill1.setVendorBillId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        bill1.setVendorId(testVendorId);
        bill1.setTotalAmount(new BigDecimal("500.00"));
        bill1.setStatus(VendorBillStatus.APPROVED);
        bill1.setBillNumber("BILL-001");

        when(billRepository.findByVendorIdAndStatusAndOpenAmountGreaterThan(
                        eq(testVendorId), eq(VendorBillStatus.APPROVED), eq(BigDecimal.ZERO), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(bill1)));
        when(allocationRepository.sumAllocatedAmountByVendorBillId(bill1.getVendorBillId()))
                .thenReturn(BigDecimal.ZERO);
        when(billRepository.findById(bill1.getVendorBillId())).thenReturn(Optional.of(bill1));

        Page<VendorBillSummaryResponse> result = service.listEligibleBills(testVendorId, PageRequest.of(0, 20));

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).getVendorBillId()).isEqualTo(bill1.getVendorBillId());
        assertThat(result.getContent().get(0).getOpenAmount()).isEqualByComparingTo("500.00");
    }

    // ========================================
    // acknowledgeGLPosted Tests
    // ========================================

    @Test
    @DisplayName("acknowledgeGLPosted should update payment to GL_POSTED status")
    void acknowledgeGLPosted_Success() {
        UUID journalEntryId = UUID.fromString("00000000-0000-0000-0000-000000000050");
        APPayment payment = buildExistingPayment(
                TEST_PAYMENT_ID, testPaymentRef, testVendorId, new BigDecimal("1500.00"), PaymentMethod.ACH);
        payment.setStatus(APPaymentStatus.GL_POST_PENDING);
        JournalEntry journalEntry = new JournalEntry();
        journalEntry.setJournalEntryId(journalEntryId);

        when(paymentRepository.findById(TEST_PAYMENT_ID)).thenReturn(Optional.of(payment));
        when(journalEntryRepository.findById(journalEntryId)).thenReturn(Optional.of(journalEntry));
        when(paymentRepository.save(any(APPayment.class))).thenReturn(payment);

        service.acknowledgeGLPosted(TEST_PAYMENT_ID, journalEntryId);

        verify(paymentRepository).save(any(APPayment.class));
        assertThat(payment.getStatus()).isEqualTo(APPaymentStatus.GL_POSTED);
        assertThat(payment.getGlJournalEntryId()).isEqualTo(journalEntryId);
    }

    @Test
    @DisplayName("acknowledgeGLPosted should throw IllegalArgumentException when payment not found")
    void acknowledgeGLPosted_NotFound() {
        UUID journalEntryId = UUID.fromString("00000000-0000-0000-0000-000000000050");
        when(paymentRepository.findById(TEST_PAYMENT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.acknowledgeGLPosted(TEST_PAYMENT_ID, journalEntryId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Payment not found");
    }

    // ========================================
    // recordGLPostFailure Tests
    // ========================================

    @Test
    @DisplayName("recordGLPostFailure should set GL_POST_FAILED status and error message")
    void recordGLPostFailure_Success() {
        APPayment payment = buildExistingPayment(
                TEST_PAYMENT_ID, testPaymentRef, testVendorId, new BigDecimal("1500.00"), PaymentMethod.ACH);
        payment.setStatus(APPaymentStatus.GL_POST_PENDING);

        when(paymentRepository.findById(TEST_PAYMENT_ID)).thenReturn(Optional.of(payment));
        when(paymentRepository.save(any(APPayment.class))).thenReturn(payment);

        service.recordGLPostFailure(TEST_PAYMENT_ID, "Journal entry creation failed");

        verify(paymentRepository).save(any(APPayment.class));
        assertThat(payment.getStatus()).isEqualTo(APPaymentStatus.GL_POST_FAILED);
        assertThat(payment.getGlPostError()).isEqualTo("Journal entry creation failed");
    }

    @Test
    @DisplayName("recordGLPostFailure should throw IllegalArgumentException when payment not found")
    void recordGLPostFailure_NotFound() {
        when(paymentRepository.findById(TEST_PAYMENT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.recordGLPostFailure(TEST_PAYMENT_ID, "Error"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Payment not found");
    }

    // ========================================
    // Helper Methods
    // ========================================

    private ExecuteAPPaymentRequest buildRequest(
            String paymentRef, UUID vendorId, BigDecimal grossAmount, PaymentMethod method) {
        return ExecuteAPPaymentRequest.builder()
                .paymentRef(paymentRef)
                .vendorId(vendorId)
                .grossAmount(grossAmount)
                .currency("USD")
                .paymentMethod(method)
                .build();
    }

    private APPayment buildExistingPayment(
            UUID paymentId, String paymentRef, UUID vendorId, BigDecimal grossAmount, PaymentMethod method) {
        APPayment payment = new APPayment();
        payment.setPaymentId(paymentId);
        payment.setPaymentRef(paymentRef);
        payment.setVendorId(vendorId);
        payment.setGrossAmount(grossAmount);
        payment.setCurrency("USD");
        payment.setPaymentMethod(method);
        payment.setStatus(APPaymentStatus.GATEWAY_SUCCEEDED);
        payment.setCreatedAt(Instant.now(TEST_CLOCK));
        return payment;
    }
}
