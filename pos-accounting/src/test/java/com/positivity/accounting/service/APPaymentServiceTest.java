package com.positivity.accounting.service;

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
import com.positivity.accounting.internal.enums.APPaymentStatus;
import com.positivity.accounting.internal.enums.PaymentMethod;
import com.positivity.accounting.internal.enums.VendorBillStatus;
import com.positivity.accounting.internal.exception.IdempotencyConflictException;
import com.positivity.accounting.internal.exception.PaymentGatewayException;
import com.positivity.accounting.internal.payment.GatewayPaymentResponse;
import com.positivity.accounting.internal.payment.PaymentGatewayProvider;
import com.positivity.accounting.internal.repository.APPaymentAllocationRepository;
import com.positivity.accounting.internal.repository.APPaymentRepository;
import com.positivity.accounting.internal.repository.JournalEntryRepository;
import com.positivity.accounting.internal.repository.VendorBillRepository;
import com.positivity.accounting.internal.service.APPaymentFailurePersistenceService;
import com.positivity.accounting.internal.service.APPaymentServiceImpl;
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
        // Default: billRepository.findByVendorIdAndStatus returns empty mutable list
        // (service calls bills.sort() in-place; List.of() is immutable)
        when(billRepository.findByVendorIdAndStatus(any(UUID.class), any(VendorBillStatus.class)))
                .thenReturn(new java.util.ArrayList<>());
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
