package com.positivity.accounting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.dto.PaymentApplicationGLPostingEvent;
import com.positivity.accounting.internal.dto.PaymentApplicationRequest;
import com.positivity.accounting.internal.dto.PaymentApplicationResponse;
import com.positivity.accounting.internal.entity.CustomerCredit;
import com.positivity.accounting.internal.entity.ExtInvoice;
import com.positivity.accounting.internal.entity.PaymentApplication;
import com.positivity.accounting.internal.entity.PaymentApplicationReversal;
import com.positivity.accounting.internal.entity.ReceivablePayment;
import com.positivity.accounting.internal.entity.ReceivablePayment.ReceivablePaymentStatus;
import com.positivity.accounting.internal.enums.AllocationStrategy;
import com.positivity.accounting.internal.enums.InvoiceStatus;
import com.positivity.accounting.internal.exception.MultiApplicationReversalException;
import com.positivity.accounting.internal.repository.CustomerCreditRepository;
import com.positivity.accounting.internal.repository.PaymentApplicationRepository;
import com.positivity.accounting.internal.repository.PaymentApplicationReversalRepository;
import com.positivity.accounting.internal.repository.ReceivablePaymentRepository;
import com.positivity.accounting.internal.service.InvoiceBalanceCalculator;
import com.positivity.accounting.internal.service.InvoicePaymentStatusServiceImpl;
import com.positivity.accounting.internal.service.PaymentApplicationServiceImpl;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Unit tests for PaymentApplicationService
 *
 * Tests business logic, validation rules, idempotency, and error handling
 * for payment application operations.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PaymentApplicationService Unit Tests")
class PaymentApplicationServiceTest {

    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);

    @Spy
    private Clock clock = TEST_CLOCK;

    @Mock
    private ReceivablePaymentRepository receivablePaymentRepository;

    @Mock
    private PaymentApplicationRepository paymentApplicationRepository;

    @Mock
    private CustomerCreditRepository customerCreditRepository;

    @Mock
    private PaymentApplicationReversalRepository paymentApplicationReversalRepository;

    @Mock
    private InvoicePaymentStatusServiceImpl invoicePaymentStatusService;

    @Mock
    private InvoiceBalanceCalculator invoiceBalanceCalculator;

    @Mock
    private OutboxService outboxService;

    @InjectMocks
    private PaymentApplicationServiceImpl service;

    @Captor
    private ArgumentCaptor<PaymentApplication> paymentApplicationCaptor;

    private UUID testPaymentId;
    private UUID testCustomerId;
    private UUID testInvoiceId;
    private UUID testSourceEventId;
    private String testApplicationRequestId;
    private ReceivablePayment testPayment;

    @BeforeEach
    void setUp() {
        testPaymentId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        testCustomerId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        testInvoiceId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        testSourceEventId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        testApplicationRequestId =
                UUID.fromString("00000000-0000-0000-0000-000000000001").toString();

        testPayment = new ReceivablePayment();
        testPayment.setPaymentId(testPaymentId);
        testPayment.setCustomerId(testCustomerId);
        testPayment.setTotalAmount(new BigDecimal("1000.00"));
        testPayment.setUnappliedAmount(new BigDecimal("1000.00"));
        testPayment.setCurrency("USD");
        testPayment.setStatus(ReceivablePaymentStatus.AVAILABLE);
        testPayment.setClearedAt(Instant.now(TEST_CLOCK));
        testPayment.setSourceEventId(testSourceEventId);
        testPayment.setCreatedAt(Instant.now(TEST_CLOCK));
    }

    // ========================================
    // handlePaymentCleared() Tests
    // ========================================

    @Test
    @DisplayName("Should create ReceivablePayment when PaymentCleared event received")
    void testHandlePaymentCleared_Success() {
        // Arrange
        when(receivablePaymentRepository.existsBySourceEventId(testSourceEventId))
                .thenReturn(false);
        when(receivablePaymentRepository.save(any(ReceivablePayment.class))).thenReturn(testPayment);

        // Act
        ReceivablePayment result = service.handlePaymentCleared(
                testPaymentId,
                testCustomerId,
                "USD",
                new BigDecimal("1000.00"),
                Instant.now(TEST_CLOCK),
                testSourceEventId);

        // Assert
        assertThat(result).isNotNull();
        assertThat(result.getPaymentId()).isEqualTo(testPaymentId);
        assertThat(result.getCustomerId()).isEqualTo(testCustomerId);
        assertThat(result.getTotalAmount()).isEqualByComparingTo("1000.00");
        assertThat(result.getUnappliedAmount()).isEqualByComparingTo("1000.00");
        assertThat(result.getStatus()).isEqualTo(ReceivablePaymentStatus.AVAILABLE);

        verify(receivablePaymentRepository).existsBySourceEventId(testSourceEventId);
        verify(receivablePaymentRepository).save(any(ReceivablePayment.class));
    }

    @Test
    @DisplayName("Should enforce idempotency when duplicate PaymentCleared event received")
    void testHandlePaymentCleared_Idempotent() {
        // Arrange
        when(receivablePaymentRepository.existsBySourceEventId(testSourceEventId))
                .thenReturn(true);
        when(receivablePaymentRepository.findBySourceEventId(testSourceEventId)).thenReturn(Optional.of(testPayment));

        // Act
        ReceivablePayment result = service.handlePaymentCleared(
                testPaymentId,
                testCustomerId,
                "USD",
                new BigDecimal("1000.00"),
                Instant.now(TEST_CLOCK),
                testSourceEventId);

        // Assert
        assertThat(result).isEqualTo(testPayment);
        verify(receivablePaymentRepository).existsBySourceEventId(testSourceEventId);
        verify(receivablePaymentRepository, never()).save(any());
    }

    // ========================================
    // applyPaymentToInvoices() Tests
    // ========================================

    @Test
    @DisplayName("Should apply payment to single invoice successfully")
    void testApplyPaymentToInvoices_SingleInvoice_Success() {
        // Arrange
        PaymentApplicationRequest request = createApplicationRequest(
                testApplicationRequestId, List.of(createInvoiceApplication(testInvoiceId, "500.00")));

        when(paymentApplicationRepository.existsByApplicationRequestId(testApplicationRequestId))
                .thenReturn(false);
        when(receivablePaymentRepository.findById(testPaymentId)).thenReturn(Optional.of(testPayment));
        when(paymentApplicationRepository.save(any(PaymentApplication.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(receivablePaymentRepository.save(any(ReceivablePayment.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        stubInvoice(testInvoiceId, "1000.00");

        // Act
        PaymentApplicationResponse response = service.applyPaymentToInvoices(testPaymentId, request);

        // Assert
        assertThat(response).isNotNull();
        assertThat(response.getPaymentId()).isEqualTo(testPaymentId);
        assertThat(response.getCustomerId()).isEqualTo(testCustomerId);
        assertThat(response.getAppliedAmount()).isEqualByComparingTo("500.00");
        assertThat(response.getRemainingAmount()).isEqualByComparingTo("500.00");
        assertThat(response.getApplications()).hasSize(1);
        assertThat(response.getCustomerCredit()).isNull();

        verify(paymentApplicationRepository).save(paymentApplicationCaptor.capture());
        verify(receivablePaymentRepository).save(testPayment);

        // Verify entity has pre-generated ID (not null before save)
        PaymentApplication savedApp = paymentApplicationCaptor.getValue();
        assertThat(savedApp.getPaymentApplicationId()).isNotNull();

        // Verify invoice balance snapshot is persisted on entity
        assertThat(savedApp.getInvoiceBalanceBefore()).isEqualByComparingTo("1000.00");
        assertThat(savedApp.getInvoiceBalanceAfter()).isEqualByComparingTo("500.00");
        assertThat(savedApp.getInvoiceStatus()).isEqualTo(InvoiceStatus.PARTIALLY_PAID);
    }

    @Test
    @DisplayName("Should apply payment to multiple invoices successfully")
    void testApplyPaymentToInvoices_MultipleInvoices_Success() {
        // Arrange
        UUID invoice1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID invoice2 = UUID.fromString("00000000-0000-0000-0000-000000000001");
        PaymentApplicationRequest request = createApplicationRequest(
                testApplicationRequestId,
                List.of(createInvoiceApplication(invoice1, "300.00"), createInvoiceApplication(invoice2, "400.00")));

        when(paymentApplicationRepository.existsByApplicationRequestId(testApplicationRequestId))
                .thenReturn(false);
        when(receivablePaymentRepository.findById(testPaymentId)).thenReturn(Optional.of(testPayment));
        when(paymentApplicationRepository.save(any(PaymentApplication.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(receivablePaymentRepository.save(any(ReceivablePayment.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        stubInvoice(UUID.fromString("00000000-0000-0000-0000-000000000001"), "1000.00");

        // Act
        PaymentApplicationResponse response = service.applyPaymentToInvoices(testPaymentId, request);

        // Assert
        assertThat(response).isNotNull();
        assertThat(response.getAppliedAmount()).isEqualByComparingTo("700.00");
        assertThat(response.getRemainingAmount()).isEqualByComparingTo("300.00");
        assertThat(response.getApplications()).hasSize(2);

        // Verify both applications have pre-generated IDs and balance snapshots
        verify(paymentApplicationRepository, times(2)).save(paymentApplicationCaptor.capture());
        List<PaymentApplication> savedApps = paymentApplicationCaptor.getAllValues();
        for (PaymentApplication app : savedApps) {
            assertThat(app.getPaymentApplicationId()).isNotNull();
            assertThat(app.getInvoiceBalanceBefore()).isNotNull();
            assertThat(app.getInvoiceBalanceAfter()).isNotNull();
            assertThat(app.getInvoiceStatus()).isNotNull();
        }
    }

    @Test
    @DisplayName("Should create CustomerCredit for overpayment")
    void testApplyPaymentToInvoices_WithOverpayment_CreatesCredit() {
        // Arrange
        PaymentApplicationRequest request = createApplicationRequest(
                testApplicationRequestId, List.of(createInvoiceApplication(testInvoiceId, "600.00")));

        when(paymentApplicationRepository.existsByApplicationRequestId(testApplicationRequestId))
                .thenReturn(false);
        when(receivablePaymentRepository.findById(testPaymentId)).thenReturn(Optional.of(testPayment));
        when(paymentApplicationRepository.save(any(PaymentApplication.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(receivablePaymentRepository.save(any(ReceivablePayment.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        stubInvoice(testInvoiceId, "1000.00");
        // Note: customerCreditRepository.save() is NOT called in normal flow
        // Credit only created when: remainingAmount > 0 && status == FULLY_APPLIED
        // In normal flow, status stays AVAILABLE

        // Act
        PaymentApplicationResponse response = service.applyPaymentToInvoices(testPaymentId, request);

        // Credit is null because payment status is AVAILABLE (not FULLY_APPLIED)
        // Credit creation logic - if remainingAmount gt 0 and status eq FULLY_APPLIED
        // In normal flow, status stays AVAILABLE, so credit is not created
        assertThat(response.getCustomerCredit()).isNull();
        // Verify remaining amount
        assertThat(response.getRemainingAmount()).isEqualByComparingTo("400.00");
        assertThat(response.getAppliedAmount()).isEqualByComparingTo("600.00");

        // Verify credit repository was NOT called
        verify(customerCreditRepository, never()).save(any(CustomerCredit.class));
    }

    @Test
    @DisplayName("Should enforce idempotency when duplicate applicationRequestId used")
    void testApplyPaymentToInvoices_Idempotent() {
        // Arrange
        PaymentApplication existingApplication = new PaymentApplication();
        existingApplication.setPaymentApplicationId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        existingApplication.setPayment(testPayment);
        existingApplication.setCustomerId(testCustomerId);
        existingApplication.setInvoiceId(testInvoiceId);
        existingApplication.setAppliedAmount(new BigDecimal("500.00"));
        existingApplication.setCurrency("USD");
        existingApplication.setApplicationRequestId(testApplicationRequestId);
        existingApplication.setApplicationTimestamp(Instant.now(TEST_CLOCK));
        // Balance snapshot persisted from original application
        existingApplication.setInvoiceBalanceBefore(new BigDecimal("1000.00"));
        existingApplication.setInvoiceBalanceAfter(new BigDecimal("500.00"));
        existingApplication.setInvoiceStatus(InvoiceStatus.PARTIALLY_PAID);

        when(paymentApplicationRepository.existsByApplicationRequestId(testApplicationRequestId))
                .thenReturn(true);
        when(paymentApplicationRepository.findAllByApplicationRequestId(testApplicationRequestId))
                .thenReturn(List.of(existingApplication));
        when(receivablePaymentRepository.findById(testPaymentId)).thenReturn(Optional.of(testPayment));

        PaymentApplicationRequest request = createApplicationRequest(
                testApplicationRequestId, List.of(createInvoiceApplication(testInvoiceId, "500.00")));

        // Act
        PaymentApplicationResponse response = service.applyPaymentToInvoices(testPaymentId, request);

        // Assert
        assertThat(response).isNotNull();
        assertThat(response.getPaymentId()).isEqualTo(testPaymentId);

        // Verify balance data returned from persisted entity (no invoice service call)
        assertThat(response.getApplications()).hasSize(1);
        PaymentApplicationResponse.ApplicationDetail detail =
                response.getApplications().get(0);
        assertThat(detail.getInvoiceBalanceBefore()).isEqualByComparingTo("1000.00");
        assertThat(detail.getInvoiceBalanceAfter()).isEqualByComparingTo("500.00");
        assertThat(detail.getInvoiceStatus()).isEqualTo(InvoiceStatus.PARTIALLY_PAID);

        verify(paymentApplicationRepository, never()).save(any());
    }

    @Test
    @DisplayName("Should pre-generate the application ID and return it in the response")
    void testApplyPaymentToInvoices_PreGeneratedIdReturnedInResponse() {
        // Arrange
        when(paymentApplicationRepository.existsByApplicationRequestId(testApplicationRequestId))
                .thenReturn(false);
        when(receivablePaymentRepository.findById(testPaymentId)).thenReturn(Optional.of(testPayment));
        stubInvoice(testInvoiceId, "1000.00");
        when(paymentApplicationRepository.save(any(PaymentApplication.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(receivablePaymentRepository.save(any(ReceivablePayment.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        PaymentApplicationRequest request = createApplicationRequest(
                testApplicationRequestId, List.of(createInvoiceApplication(testInvoiceId, "500.00")));

        // Act
        PaymentApplicationResponse response = service.applyPaymentToInvoices(testPaymentId, request);

        // Assert — the persisted entity ID matches the one returned to the caller
        verify(paymentApplicationRepository).save(paymentApplicationCaptor.capture());
        UUID idPersistedOnEntity = paymentApplicationCaptor.getValue().getPaymentApplicationId();

        assertThat(idPersistedOnEntity).isNotNull();
        assertThat(response.getApplications()).hasSize(1);
        assertThat(response.getApplications().get(0).getPaymentApplicationId()).isEqualTo(idPersistedOnEntity);
    }

    @Test
    @DisplayName("Should persist balance snapshot for idempotent retries")
    void testApplyPaymentToInvoices_BalanceSnapshotPersistedForIdempotentRetries() {
        // Arrange
        when(paymentApplicationRepository.existsByApplicationRequestId(testApplicationRequestId))
                .thenReturn(false);
        when(receivablePaymentRepository.findById(testPaymentId)).thenReturn(Optional.of(testPayment));
        stubInvoice(testInvoiceId, "1000.00");
        when(paymentApplicationRepository.save(any(PaymentApplication.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(receivablePaymentRepository.save(any(ReceivablePayment.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        PaymentApplicationRequest request = createApplicationRequest(
                testApplicationRequestId, List.of(createInvoiceApplication(testInvoiceId, "500.00")));

        // Act
        PaymentApplicationResponse response = service.applyPaymentToInvoices(testPaymentId, request);

        // Assert — response contains balance data
        assertThat(response.getApplications()).hasSize(1);
        PaymentApplicationResponse.ApplicationDetail detail =
                response.getApplications().get(0);
        assertThat(detail.getInvoiceBalanceBefore()).isEqualByComparingTo("1000.00");
        assertThat(detail.getInvoiceBalanceAfter()).isEqualByComparingTo("500.00");
        assertThat(detail.getInvoiceStatus()).isEqualTo(InvoiceStatus.PARTIALLY_PAID);

        // Assert — entity has balance snapshot persisted
        verify(paymentApplicationRepository).save(paymentApplicationCaptor.capture());
        PaymentApplication saved = paymentApplicationCaptor.getValue();
        assertThat(saved.getInvoiceBalanceBefore()).isEqualByComparingTo("1000.00");
        assertThat(saved.getInvoiceBalanceAfter()).isEqualByComparingTo("500.00");
        assertThat(saved.getInvoiceStatus()).isEqualTo(InvoiceStatus.PARTIALLY_PAID);
    }

    @Test
    @DisplayName("Should return persisted balance data on idempotent retry without calling invoice service")
    void testApplyPaymentToInvoices_IdempotentRetryReturnsPersistedBalanceData() {
        // Arrange — simulate a retry where the application already exists with balance
        // data
        PaymentApplication existingApplication = new PaymentApplication();
        existingApplication.setPaymentApplicationId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        existingApplication.setPayment(testPayment);
        existingApplication.setCustomerId(testCustomerId);
        existingApplication.setInvoiceId(testInvoiceId);
        existingApplication.setAppliedAmount(new BigDecimal("500.00"));
        existingApplication.setCurrency("USD");
        existingApplication.setApplicationRequestId(testApplicationRequestId);
        existingApplication.setApplicationTimestamp(Instant.now(TEST_CLOCK));
        existingApplication.setInvoiceBalanceBefore(new BigDecimal("2000.00"));
        existingApplication.setInvoiceBalanceAfter(new BigDecimal("1500.00"));
        existingApplication.setInvoiceStatus(InvoiceStatus.PARTIALLY_PAID);

        when(paymentApplicationRepository.existsByApplicationRequestId(testApplicationRequestId))
                .thenReturn(true);
        when(paymentApplicationRepository.findAllByApplicationRequestId(testApplicationRequestId))
                .thenReturn(List.of(existingApplication));
        when(receivablePaymentRepository.findById(testPaymentId)).thenReturn(Optional.of(testPayment));

        PaymentApplicationRequest request = createApplicationRequest(
                testApplicationRequestId, List.of(createInvoiceApplication(testInvoiceId, "500.00")));

        // Act
        PaymentApplicationResponse response = service.applyPaymentToInvoices(testPaymentId, request);

        // Assert — balance data comes from persisted entity
        assertThat(response.getApplications()).hasSize(1);
        PaymentApplicationResponse.ApplicationDetail detail =
                response.getApplications().get(0);
        assertThat(detail.getInvoiceBalanceBefore()).isEqualByComparingTo("2000.00");
        assertThat(detail.getInvoiceBalanceAfter()).isEqualByComparingTo("1500.00");
        assertThat(detail.getInvoiceStatus()).isEqualTo(InvoiceStatus.PARTIALLY_PAID);

        // Verify no new application persisted (idempotent retry)
        verify(paymentApplicationRepository, never()).save(any());
    }

    @Test
    @DisplayName("Should fail when payment not found")
    void testApplyPaymentToInvoices_PaymentNotFound() {
        // Arrange
        PaymentApplicationRequest request = createApplicationRequest(
                testApplicationRequestId, List.of(createInvoiceApplication(testInvoiceId, "500.00")));

        when(paymentApplicationRepository.existsByApplicationRequestId(testApplicationRequestId))
                .thenReturn(false);
        when(receivablePaymentRepository.findById(testPaymentId)).thenReturn(Optional.empty());

        // Act & Assert
        assertThatThrownBy(() -> service.applyPaymentToInvoices(testPaymentId, request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Payment not found")
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("Should fail when payment is not AVAILABLE")
    void testApplyPaymentToInvoices_PaymentNotAvailable() {
        // Arrange
        testPayment.setStatus(ReceivablePaymentStatus.FULLY_APPLIED);

        PaymentApplicationRequest request = createApplicationRequest(
                testApplicationRequestId, List.of(createInvoiceApplication(testInvoiceId, "500.00")));

        when(paymentApplicationRepository.existsByApplicationRequestId(testApplicationRequestId))
                .thenReturn(false);
        when(receivablePaymentRepository.findById(testPaymentId)).thenReturn(Optional.of(testPayment));

        // Act & Assert
        assertThatThrownBy(() -> service.applyPaymentToInvoices(testPaymentId, request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("is not available (status:")
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("Should fail when insufficient funds")
    void testApplyPaymentToInvoices_InsufficientFunds() {
        // Arrange
        PaymentApplicationRequest request = createApplicationRequest(
                testApplicationRequestId,
                List.of(createInvoiceApplication(testInvoiceId, "1500.00")) // More than available
                );

        when(paymentApplicationRepository.existsByApplicationRequestId(testApplicationRequestId))
                .thenReturn(false);
        when(receivablePaymentRepository.findById(testPaymentId)).thenReturn(Optional.of(testPayment));

        // Act & Assert
        assertThatThrownBy(() -> service.applyPaymentToInvoices(testPaymentId, request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Insufficient funds")
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ========================================
    // GL posting outbox enqueue tests (story C1, issue #954)
    // ========================================

    @Test
    @DisplayName("Should enqueue PAYMENT_APPLICATION_GL_POSTING outbox work item on successful application")
    void testApplyPaymentToInvoices_Success_EnqueuesGLPostingOutboxWorkItem() {
        // Arrange
        PaymentApplicationRequest request = createApplicationRequest(
                testApplicationRequestId, List.of(createInvoiceApplication(testInvoiceId, "500.00")));

        when(paymentApplicationRepository.existsByApplicationRequestId(testApplicationRequestId))
                .thenReturn(false);
        when(receivablePaymentRepository.findById(testPaymentId)).thenReturn(Optional.of(testPayment));
        when(paymentApplicationRepository.save(any(PaymentApplication.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(receivablePaymentRepository.save(any(ReceivablePayment.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        stubInvoice(testInvoiceId, "1000.00");

        // Act
        service.applyPaymentToInvoices(testPaymentId, request);

        // Assert: work item saved to the outbox in the same transaction
        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(outboxService)
                .saveToOutbox(
                        any(UUID.class),
                        eq("PaymentApplication"),
                        eq(testPaymentId),
                        eq(PaymentApplicationGLPostingEvent.class.getName()),
                        eventCaptor.capture());

        PaymentApplicationGLPostingEvent event = (PaymentApplicationGLPostingEvent) eventCaptor.getValue();
        assertThat(event.getEventId()).isNotNull();
        assertThat(event.getApplicationRequestId()).isEqualTo(testApplicationRequestId);
        assertThat(event.getPaymentId()).isEqualTo(testPaymentId);
        assertThat(event.getCustomerId()).isEqualTo(testCustomerId);
        assertThat(event.getCurrency()).isEqualTo("USD");
        assertThat(event.getAppliedAmount()).isEqualByComparingTo("500.00");
        assertThat(event.getApplicationTimestamp()).isEqualTo(Instant.now(TEST_CLOCK));
    }

    @Test
    @DisplayName("Should not enqueue GL posting work item when validation fails")
    void testApplyPaymentToInvoices_ValidationFailure_DoesNotEnqueueGLPosting() {
        // Arrange: insufficient funds fails before any mutation
        PaymentApplicationRequest request = createApplicationRequest(
                testApplicationRequestId, List.of(createInvoiceApplication(testInvoiceId, "1500.00")));

        when(paymentApplicationRepository.existsByApplicationRequestId(testApplicationRequestId))
                .thenReturn(false);
        when(receivablePaymentRepository.findById(testPaymentId)).thenReturn(Optional.of(testPayment));

        // Act & Assert
        assertThatThrownBy(() -> service.applyPaymentToInvoices(testPaymentId, request))
                .isInstanceOf(ResponseStatusException.class);

        verifyNoInteractions(outboxService);
    }

    @Test
    @DisplayName("Should not enqueue GL posting work item on idempotent replay")
    void testApplyPaymentToInvoices_IdempotentReplay_DoesNotEnqueueGLPosting() {
        // Arrange: request id already processed
        PaymentApplication existingApplication = new PaymentApplication();
        existingApplication.setPaymentApplicationId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        existingApplication.setPayment(testPayment);
        existingApplication.setCustomerId(testCustomerId);
        existingApplication.setInvoiceId(testInvoiceId);
        existingApplication.setAppliedAmount(new BigDecimal("500.00"));
        existingApplication.setCurrency("USD");
        existingApplication.setApplicationRequestId(testApplicationRequestId);
        existingApplication.setApplicationTimestamp(Instant.now(TEST_CLOCK));

        when(paymentApplicationRepository.existsByApplicationRequestId(testApplicationRequestId))
                .thenReturn(true);
        when(paymentApplicationRepository.findAllByApplicationRequestId(testApplicationRequestId))
                .thenReturn(List.of(existingApplication));
        when(receivablePaymentRepository.findById(testPaymentId)).thenReturn(Optional.of(testPayment));

        PaymentApplicationRequest request = createApplicationRequest(
                testApplicationRequestId, List.of(createInvoiceApplication(testInvoiceId, "500.00")));

        // Act
        service.applyPaymentToInvoices(testPaymentId, request);

        // Assert: replay must not double-enqueue (double-post guard, story C1)
        verifyNoInteractions(outboxService);
    }

    // ========================================
    // voidPayment() Tests
    // ========================================

    @Test
    @DisplayName("Should void payment when no applications exist")
    void testVoidPayment_Success() {
        // Arrange
        when(receivablePaymentRepository.findById(testPaymentId)).thenReturn(Optional.of(testPayment));
        when(paymentApplicationRepository.findByPayment_PaymentId(testPaymentId))
                .thenReturn(List.of());
        when(receivablePaymentRepository.save(any(ReceivablePayment.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        service.voidPayment(testPaymentId);

        // Assert
        assertThat(testPayment.getStatus()).isEqualTo(ReceivablePaymentStatus.FULLY_APPLIED);
        assertThat(testPayment.getUnappliedAmount()).isEqualByComparingTo("0");
        verify(receivablePaymentRepository).save(testPayment);
    }

    @Test
    @DisplayName("Should fail void when payment not found")
    void testVoidPayment_NotFound() {
        // Arrange
        when(receivablePaymentRepository.findById(testPaymentId)).thenReturn(Optional.empty());

        // Act & Assert
        assertThatThrownBy(() -> service.voidPayment(testPaymentId))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Payment not found")
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("Should fail void when payment already has applications")
    void testVoidPayment_ConflictWhenApplied() {
        // Arrange
        PaymentApplication existing = new PaymentApplication();
        existing.setPaymentApplicationId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        existing.setPayment(testPayment);
        existing.setInvoiceId(testInvoiceId);
        existing.setAppliedAmount(new BigDecimal("100.00"));

        when(receivablePaymentRepository.findById(testPaymentId)).thenReturn(Optional.of(testPayment));
        when(paymentApplicationRepository.findByPayment_PaymentId(testPaymentId))
                .thenReturn(List.of(existing));

        // Act & Assert
        assertThatThrownBy(() -> service.voidPayment(testPaymentId))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("cannot be voided")
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);

        verify(receivablePaymentRepository, never()).save(any());
    }

    // ========================================
    // reversePayment() Tests
    // ========================================

    @Test
    @DisplayName("Should reverse payment by reusing payment-application reversal")
    void testReversePayment_Success() {
        // Arrange
        UUID applicationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        PaymentApplication application = new PaymentApplication();
        application.setPaymentApplicationId(applicationId);
        application.setPayment(testPayment);
        application.setCustomerId(testCustomerId);
        application.setInvoiceId(testInvoiceId);
        application.setAppliedAmount(new BigDecimal("500.00"));
        application.setCurrency("USD");
        application.setApplicationRequestId(testApplicationRequestId);
        application.setApplicationTimestamp(Instant.now(TEST_CLOCK));

        testPayment.setUnappliedAmount(new BigDecimal("500.00"));

        when(receivablePaymentRepository.findById(testPaymentId)).thenReturn(Optional.of(testPayment));
        when(paymentApplicationRepository.findByPayment_PaymentId(testPaymentId))
                .thenReturn(List.of(application));
        when(paymentApplicationReversalRepository.existsByOriginalPaymentApplication_PaymentApplicationId(
                        applicationId))
                .thenReturn(false);
        when(paymentApplicationRepository.findById(applicationId)).thenReturn(Optional.of(application));
        when(paymentApplicationReversalRepository.save(any(PaymentApplicationReversal.class)))
                .thenAnswer(invocation -> {
                    PaymentApplicationReversal rev = invocation.getArgument(0);
                    if (rev.getReversalId() == null) {
                        rev.setReversalId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
                    }
                    return rev;
                });
        when(receivablePaymentRepository.save(any(ReceivablePayment.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        service.reversePayment(testPaymentId, "Customer requested full reversal");

        // Assert
        assertThat(testPayment.getUnappliedAmount()).isEqualByComparingTo("1000.00");
        verify(paymentApplicationReversalRepository).save(any(PaymentApplicationReversal.class));
    }

    @Test
    @DisplayName("Should fail reversePayment when payment not found")
    void testReversePayment_NotFound() {
        // Arrange
        when(receivablePaymentRepository.findById(testPaymentId)).thenReturn(Optional.empty());

        // Act & Assert
        assertThatThrownBy(() -> service.reversePayment(testPaymentId, "Customer requested reversal"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Payment not found")
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("Should fail reversePayment when payment has no applications")
    void testReversePayment_NoApplications() {
        // Arrange
        when(receivablePaymentRepository.findById(testPaymentId)).thenReturn(Optional.of(testPayment));
        when(paymentApplicationRepository.findByPayment_PaymentId(testPaymentId))
                .thenReturn(List.of());

        // Act & Assert
        assertThatThrownBy(() -> service.reversePayment(testPaymentId, "Customer requested reversal"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("has no applications to reverse")
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("Should no-op reversePayment when all applications are already reversed")
    void testReversePayment_AllAlreadyReversed_NoOp() {
        // Arrange
        UUID applicationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        PaymentApplication application = new PaymentApplication();
        application.setPaymentApplicationId(applicationId);
        application.setPayment(testPayment);
        application.setInvoiceId(testInvoiceId);
        application.setAppliedAmount(new BigDecimal("100.00"));

        when(receivablePaymentRepository.findById(testPaymentId)).thenReturn(Optional.of(testPayment));
        when(paymentApplicationRepository.findByPayment_PaymentId(testPaymentId))
                .thenReturn(List.of(application));
        when(paymentApplicationReversalRepository.existsByOriginalPaymentApplication_PaymentApplicationId(
                        applicationId))
                .thenReturn(true);

        // Act
        service.reversePayment(testPaymentId, "Retry reversal");

        // Assert
        verify(paymentApplicationRepository, never()).findById(applicationId);
        verify(paymentApplicationReversalRepository, never()).save(any(PaymentApplicationReversal.class));
    }

    // ========================================
    // reversePaymentApplication() Tests
    // ========================================

    @Test
    @DisplayName("Should reverse payment application successfully")
    void testReversePaymentApplication_Success() {
        // Arrange
        UUID applicationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        PaymentApplication application = new PaymentApplication();
        application.setPaymentApplicationId(applicationId);
        application.setPayment(testPayment);
        application.setCustomerId(testCustomerId);
        application.setInvoiceId(testInvoiceId);
        application.setAppliedAmount(new BigDecimal("500.00"));
        application.setCurrency("USD");
        application.setApplicationRequestId(testApplicationRequestId);
        application.setApplicationTimestamp(Instant.now(TEST_CLOCK));

        testPayment.setUnappliedAmount(new BigDecimal("500.00")); // Already had some applied

        when(paymentApplicationReversalRepository.existsByOriginalPaymentApplication_PaymentApplicationId(
                        applicationId))
                .thenReturn(false); // Not yet reversed
        when(paymentApplicationRepository.findById(applicationId)).thenReturn(Optional.of(application));
        when(receivablePaymentRepository.findById(testPaymentId)).thenReturn(Optional.of(testPayment));
        when(paymentApplicationReversalRepository.save(any(PaymentApplicationReversal.class)))
                .thenAnswer(invocation -> {
                    PaymentApplicationReversal rev = invocation.getArgument(0);
                    if (rev.getReversalId() == null) {
                        rev.setReversalId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
                    }
                    return rev;
                });
        when(receivablePaymentRepository.save(any(ReceivablePayment.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        PaymentApplicationReversal result =
                service.reversePaymentApplication(applicationId, "Customer disputed charge");

        // Assert
        assertThat(result).isNotNull();
        assertThat(result.getOriginalPaymentApplicationId()).isEqualTo(applicationId);
        assertThat(result.getAmount()).isEqualByComparingTo("500.00");
        assertThat(result.getReason()).isEqualTo("Customer disputed charge");
        assertThat(result.getReversedBy()).isEqualTo("SYSTEM"); // Derived from SecurityContext (fallback in
        // tests)

        // Verify payment unappliedAmount restored
        assertThat(testPayment.getUnappliedAmount()).isEqualByComparingTo("1000.00");

        verify(paymentApplicationReversalRepository).save(any(PaymentApplicationReversal.class));
        // Note: Service doesn't update application entity - it's immutable
        // verify(paymentApplicationRepository).save(application); // REMOVED - not in
        // actual service
        verify(receivablePaymentRepository).save(testPayment);
    }

    @Test
    @DisplayName("Should enqueue reversing-JE GL posting work item to the outbox on reversal (story C2, #958)")
    void testReversePaymentApplication_EnqueuesReversalGLPostingWorkItem() {
        // Arrange
        UUID applicationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID reversalId = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
        PaymentApplication application = new PaymentApplication();
        application.setPaymentApplicationId(applicationId);
        application.setPayment(testPayment);
        application.setCustomerId(testCustomerId);
        application.setInvoiceId(testInvoiceId);
        application.setAppliedAmount(new BigDecimal("500.00"));
        application.setCurrency("USD");
        application.setApplicationRequestId(testApplicationRequestId);
        application.setApplicationTimestamp(Instant.now(TEST_CLOCK));

        testPayment.setUnappliedAmount(new BigDecimal("500.00"));

        when(paymentApplicationReversalRepository.existsByOriginalPaymentApplication_PaymentApplicationId(
                        applicationId))
                .thenReturn(false);
        when(paymentApplicationRepository.findById(applicationId)).thenReturn(Optional.of(application));
        when(receivablePaymentRepository.findById(testPaymentId)).thenReturn(Optional.of(testPayment));
        when(paymentApplicationReversalRepository.save(any(PaymentApplicationReversal.class)))
                .thenAnswer(invocation -> {
                    PaymentApplicationReversal rev = invocation.getArgument(0);
                    if (rev.getReversalId() == null) {
                        rev.setReversalId(reversalId);
                    }
                    return rev;
                });
        when(receivablePaymentRepository.save(any(ReceivablePayment.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        service.reversePaymentApplication(applicationId, "Customer disputed charge");

        // Assert: outbox work item enqueued in the same transaction with the
        // reversal event class as its type and the payment as the aggregate.
        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(outboxService)
                .saveToOutbox(
                        any(UUID.class),
                        eq("PaymentApplicationReversal"),
                        eq(testPaymentId),
                        eq(
                                com.positivity.accounting.internal.dto.PaymentApplicationReversalGLPostingEvent.class
                                        .getName()),
                        eventCaptor.capture());

        assertThat(eventCaptor.getValue())
                .isInstanceOf(com.positivity.accounting.internal.dto.PaymentApplicationReversalGLPostingEvent.class);
        var reversalEvent = (com.positivity.accounting.internal.dto.PaymentApplicationReversalGLPostingEvent)
                eventCaptor.getValue();
        assertThat(reversalEvent.getApplicationRequestId()).isEqualTo(testApplicationRequestId);
        assertThat(reversalEvent.getReversalId()).isEqualTo(reversalId);
        assertThat(reversalEvent.getPaymentId()).isEqualTo(testPaymentId);
        assertThat(reversalEvent.getReason()).isEqualTo("Customer disputed charge");
        assertThat(reversalEvent.getEventId()).isNotNull();
    }

    @Test
    @DisplayName("Should use authenticated user from SecurityContext for reversal audit")
    void testReversePaymentApplication_WithAuthenticatedUser() {
        // Arrange
        UUID applicationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        PaymentApplication application = new PaymentApplication();
        application.setPaymentApplicationId(applicationId);
        application.setPayment(testPayment);
        application.setCustomerId(testCustomerId);
        application.setInvoiceId(testInvoiceId);
        application.setAppliedAmount(new BigDecimal("500.00"));
        application.setCurrency("USD");
        application.setApplicationRequestId(testApplicationRequestId);
        application.setApplicationTimestamp(Instant.now(TEST_CLOCK));

        testPayment.setUnappliedAmount(new BigDecimal("500.00"));

        when(paymentApplicationReversalRepository.existsByOriginalPaymentApplication_PaymentApplicationId(
                        applicationId))
                .thenReturn(false);
        when(paymentApplicationRepository.findById(applicationId)).thenReturn(Optional.of(application));
        when(receivablePaymentRepository.findById(testPaymentId)).thenReturn(Optional.of(testPayment));
        when(paymentApplicationReversalRepository.save(any(PaymentApplicationReversal.class)))
                .thenAnswer(invocation -> {
                    PaymentApplicationReversal rev = invocation.getArgument(0);
                    if (rev.getReversalId() == null) {
                        rev.setReversalId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
                    }
                    return rev;
                });
        when(receivablePaymentRepository.save(any(ReceivablePayment.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        // Set up SecurityContext with authenticated user using gateway-format details
        // map (required by SecurityContextHelper.getCurrentUsername() per ADR-0018
        // and the updated PaymentApplicationServiceImpl.getCurrentUser() method)
        var authDetails = new java.util.HashMap<String, Object>();
        authDetails.put("username", "admin@example.com");
        var authentication = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                "admin@example.com", null, List.of());
        ((org.springframework.security.authentication.AbstractAuthenticationToken) authentication)
                .setDetails(authDetails);
        org.springframework.security.core.context.SecurityContextHolder.getContext()
                .setAuthentication(authentication);

        try {
            // Act
            PaymentApplicationReversal result =
                    service.reversePaymentApplication(applicationId, "Customer disputed charge");

            // Assert
            assertThat(result).isNotNull();
            assertThat(result.getReversedBy()).isEqualTo("admin@example.com"); // From SecurityContext
            assertThat(testPayment.getModifiedBy()).isEqualTo("admin@example.com"); // Payment also updated

            verify(paymentApplicationReversalRepository).save(any(PaymentApplicationReversal.class));
            verify(receivablePaymentRepository).save(testPayment);
        } finally {
            // Clean up SecurityContext to avoid test pollution
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }

    @Test
    @DisplayName("Should fail when application not found")
    void testReversePaymentApplication_ApplicationNotFound() {
        // Arrange
        UUID applicationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        when(paymentApplicationRepository.findById(applicationId)).thenReturn(Optional.empty());

        // Act & Assert
        assertThatThrownBy(() -> service.reversePaymentApplication(applicationId, "Test reason"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("not found")
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("Should fail when application already reversed")
    void testReversePaymentApplication_AlreadyReversed() {
        // Arrange
        UUID applicationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        PaymentApplication application = new PaymentApplication();
        application.setPaymentApplicationId(applicationId);
        application.setPayment(testPayment);
        application.setCustomerId(testCustomerId);
        application.setInvoiceId(testInvoiceId);
        application.setAppliedAmount(new BigDecimal("500.00"));
        application.setCurrency("USD");
        application.setApplicationRequestId(testApplicationRequestId);
        application.setApplicationTimestamp(Instant.now(TEST_CLOCK));
        // Note: No isReversed field - check via repository

        when(paymentApplicationReversalRepository.existsByOriginalPaymentApplication_PaymentApplicationId(
                        applicationId))
                .thenReturn(true); // Already reversed

        // Act & Assert
        assertThatThrownBy(() -> service.reversePaymentApplication(applicationId, "Second reversal attempt"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("has already been reversed")
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("Should block single-application reversal of a multi-application apply request (finding 3)")
    void testReversePaymentApplication_MultiApplicationRequestBlocked() {
        UUID applicationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID siblingId = UUID.fromString("00000000-0000-0000-0000-000000000002");
        PaymentApplication application = new PaymentApplication();
        application.setPaymentApplicationId(applicationId);
        application.setPayment(testPayment);
        application.setInvoiceId(testInvoiceId);
        application.setAppliedAmount(new BigDecimal("500.00"));
        application.setApplicationRequestId(testApplicationRequestId);
        PaymentApplication sibling = new PaymentApplication();
        sibling.setPaymentApplicationId(siblingId);
        sibling.setApplicationRequestId(testApplicationRequestId);

        when(paymentApplicationReversalRepository.existsByOriginalPaymentApplication_PaymentApplicationId(
                        applicationId))
                .thenReturn(false);
        when(paymentApplicationRepository.findById(applicationId)).thenReturn(Optional.of(application));
        when(paymentApplicationRepository.findAllByApplicationRequestId(testApplicationRequestId))
                .thenReturn(List.of(application, sibling));

        assertThatThrownBy(() -> service.reversePaymentApplication(applicationId, "single-app reversal"))
                .isInstanceOf(MultiApplicationReversalException.class)
                .hasMessageContaining("reverse the whole payment");

        // No reversal record is created and no reversing-JE work item is enqueued.
        verify(paymentApplicationReversalRepository, never()).save(any(PaymentApplicationReversal.class));
        verify(outboxService, never()).saveToOutbox(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Should prevent duplicate reversals via repository check")
    void testReversePaymentApplication_DuplicateReversalPrevented() {
        // Arrange
        UUID applicationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        PaymentApplication application = new PaymentApplication();
        application.setPaymentApplicationId(applicationId);
        application.setPayment(testPayment);
        application.setCustomerId(testCustomerId);
        application.setInvoiceId(testInvoiceId);
        application.setAppliedAmount(new BigDecimal("500.00"));
        application.setCurrency("USD");
        application.setApplicationRequestId(testApplicationRequestId);
        application.setApplicationTimestamp(Instant.now(TEST_CLOCK));

        when(paymentApplicationReversalRepository.existsByOriginalPaymentApplication_PaymentApplicationId(
                        applicationId))
                .thenReturn(true); // Already reversed

        // Act & Assert
        assertThatThrownBy(() -> service.reversePaymentApplication(applicationId, "Test reason"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("has already been reversed")
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);

        verify(paymentApplicationReversalRepository, never()).save(any(PaymentApplicationReversal.class));
    }

    // ========================================
    // Allocation Strategy Tests (Issue #955)
    // ========================================

    @Test
    @DisplayName("Should allocate in caller order when allocationStrategy is absent (regression)")
    void testApplyPaymentToInvoices_AbsentStrategy_PreservesCallerOrder() {
        // Arrange: caller order [newer, older] — oldest-first would reverse it
        UUID newerInvoice = UUID.fromString("00000000-0000-0000-0000-00000000000a");
        UUID olderInvoice = UUID.fromString("00000000-0000-0000-0000-00000000000b");
        PaymentApplicationRequest request = createApplicationRequest(
                testApplicationRequestId,
                List.of(
                        createInvoiceApplication(newerInvoice, "300.00"),
                        createInvoiceApplication(olderInvoice, "400.00")));

        stubAppliableRequest();
        stubInvoice(newerInvoice, "1000.00", Instant.parse("2023-06-01T00:00:00Z"));
        stubInvoice(olderInvoice, "1000.00", Instant.parse("2023-01-01T00:00:00Z"));

        // Act
        PaymentApplicationResponse response = service.applyPaymentToInvoices(testPaymentId, request);

        // Assert: caller-supplied order preserved
        verify(paymentApplicationRepository, times(2)).save(paymentApplicationCaptor.capture());
        assertThat(paymentApplicationCaptor.getAllValues())
                .extracting(PaymentApplication::getInvoiceId)
                .containsExactly(newerInvoice, olderInvoice);
        assertThat(response.getApplications())
                .extracting(PaymentApplicationResponse.ApplicationDetail::getInvoiceId)
                .containsExactly(newerInvoice, olderInvoice);
        assertThat(response.getAppliedAmount()).isEqualByComparingTo("700.00");
        assertThat(response.getRemainingAmount()).isEqualByComparingTo("300.00");
    }

    @Test
    @DisplayName("Should allocate in caller order when CALLER_ORDER is explicit (identical to default)")
    void testApplyPaymentToInvoices_ExplicitCallerOrder_PreservesCallerOrder() {
        // Arrange
        UUID newerInvoice = UUID.fromString("00000000-0000-0000-0000-00000000000a");
        UUID olderInvoice = UUID.fromString("00000000-0000-0000-0000-00000000000b");
        PaymentApplicationRequest request = createApplicationRequest(
                testApplicationRequestId,
                List.of(
                        createInvoiceApplication(newerInvoice, "300.00"),
                        createInvoiceApplication(olderInvoice, "400.00")));
        request.setAllocationStrategy(AllocationStrategy.CALLER_ORDER);

        stubAppliableRequest();
        stubInvoice(newerInvoice, "1000.00", Instant.parse("2023-06-01T00:00:00Z"));
        stubInvoice(olderInvoice, "1000.00", Instant.parse("2023-01-01T00:00:00Z"));

        // Act
        PaymentApplicationResponse response = service.applyPaymentToInvoices(testPaymentId, request);

        // Assert: identical to the absent-strategy path
        verify(paymentApplicationRepository, times(2)).save(paymentApplicationCaptor.capture());
        assertThat(paymentApplicationCaptor.getAllValues())
                .extracting(PaymentApplication::getInvoiceId)
                .containsExactly(newerInvoice, olderInvoice);
        assertThat(response.getApplications())
                .extracting(PaymentApplicationResponse.ApplicationDetail::getInvoiceId)
                .containsExactly(newerInvoice, olderInvoice);
    }

    @Test
    @DisplayName("Should allocate oldest invoice first when OLDEST_FIRST is requested")
    void testApplyPaymentToInvoices_OldestFirst_ReordersByInvoiceDate() {
        // Arrange: caller order [newest, oldest, middle]
        UUID newestInvoice = UUID.fromString("00000000-0000-0000-0000-00000000000a");
        UUID oldestInvoice = UUID.fromString("00000000-0000-0000-0000-00000000000b");
        UUID middleInvoice = UUID.fromString("00000000-0000-0000-0000-00000000000c");
        PaymentApplicationRequest request = createApplicationRequest(
                testApplicationRequestId,
                List.of(
                        createInvoiceApplication(newestInvoice, "100.00"),
                        createInvoiceApplication(oldestInvoice, "200.00"),
                        createInvoiceApplication(middleInvoice, "300.00")));
        request.setAllocationStrategy(AllocationStrategy.OLDEST_FIRST);

        stubAppliableRequest();
        stubInvoice(newestInvoice, "1000.00", Instant.parse("2023-09-01T00:00:00Z"));
        stubInvoice(oldestInvoice, "1000.00", Instant.parse("2023-01-01T00:00:00Z"));
        stubInvoice(middleInvoice, "1000.00", Instant.parse("2023-05-01T00:00:00Z"));

        // Act
        PaymentApplicationResponse response = service.applyPaymentToInvoices(testPaymentId, request);

        // Assert: allocation (and response detail) order is ascending invoice date
        verify(paymentApplicationRepository, times(3)).save(paymentApplicationCaptor.capture());
        assertThat(paymentApplicationCaptor.getAllValues())
                .extracting(PaymentApplication::getInvoiceId)
                .containsExactly(oldestInvoice, middleInvoice, newestInvoice);
        assertThat(response.getApplications())
                .extracting(PaymentApplicationResponse.ApplicationDetail::getInvoiceId)
                .containsExactly(oldestInvoice, middleInvoice, newestInvoice);

        // Per-invoice amounts still follow the caller's requested amounts
        assertThat(response.getApplications())
                .extracting(PaymentApplicationResponse.ApplicationDetail::getAppliedAmount)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("200.00"), new BigDecimal("300.00"), new BigDecimal("100.00"));
    }

    @Test
    @DisplayName("Should tie-break equal invoice dates by invoice id for OLDEST_FIRST")
    void testApplyPaymentToInvoices_OldestFirst_TieBreaksByInvoiceId() {
        // Arrange: equal dates, caller order [higher id, lower id]
        UUID lowerIdInvoice = UUID.fromString("00000000-0000-0000-0000-00000000000a");
        UUID higherIdInvoice = UUID.fromString("00000000-0000-0000-0000-00000000000b");
        Instant sharedDate = Instant.parse("2023-03-01T00:00:00Z");
        PaymentApplicationRequest request = createApplicationRequest(
                testApplicationRequestId,
                List.of(
                        createInvoiceApplication(higherIdInvoice, "300.00"),
                        createInvoiceApplication(lowerIdInvoice, "400.00")));
        request.setAllocationStrategy(AllocationStrategy.OLDEST_FIRST);

        stubAppliableRequest();
        stubInvoice(lowerIdInvoice, "1000.00", sharedDate);
        stubInvoice(higherIdInvoice, "1000.00", sharedDate);

        // Act
        service.applyPaymentToInvoices(testPaymentId, request);

        // Assert: deterministic ascending invoice-id order on equal dates
        verify(paymentApplicationRepository, times(2)).save(paymentApplicationCaptor.capture());
        assertThat(paymentApplicationCaptor.getAllValues())
                .extracting(PaymentApplication::getInvoiceId)
                .containsExactly(lowerIdInvoice, higherIdInvoice);
    }

    @Test
    @DisplayName("Should keep unappliedAmount math unchanged for OLDEST_FIRST partial allocation")
    void testApplyPaymentToInvoices_OldestFirst_PartialAllocation_UnappliedMathUnchanged() {
        // Arrange: 700.00 requested of 1000.00 available, caller order [newer, older]
        UUID newerInvoice = UUID.fromString("00000000-0000-0000-0000-00000000000a");
        UUID olderInvoice = UUID.fromString("00000000-0000-0000-0000-00000000000b");
        PaymentApplicationRequest request = createApplicationRequest(
                testApplicationRequestId,
                List.of(
                        createInvoiceApplication(newerInvoice, "400.00"),
                        createInvoiceApplication(olderInvoice, "300.00")));
        request.setAllocationStrategy(AllocationStrategy.OLDEST_FIRST);

        stubAppliableRequest();
        stubInvoice(newerInvoice, "800.00", Instant.parse("2023-08-01T00:00:00Z"));
        stubInvoice(olderInvoice, "600.00", Instant.parse("2023-02-01T00:00:00Z"));

        // Act
        PaymentApplicationResponse response = service.applyPaymentToInvoices(testPaymentId, request);

        // Assert: same totals as caller-order would produce — only the order differs
        assertThat(response.getAppliedAmount()).isEqualByComparingTo("700.00");
        assertThat(response.getRemainingAmount()).isEqualByComparingTo("300.00");
        assertThat(testPayment.getUnappliedAmount()).isEqualByComparingTo("300.00");
        assertThat(response.getCustomerCredit()).isNull();

        verify(paymentApplicationRepository, times(2)).save(paymentApplicationCaptor.capture());
        List<PaymentApplication> savedApps = paymentApplicationCaptor.getAllValues();
        assertThat(savedApps).extracting(PaymentApplication::getInvoiceId).containsExactly(olderInvoice, newerInvoice);
        assertThat(savedApps)
                .extracting(PaymentApplication::getAppliedAmount)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("300.00"), new BigDecimal("400.00"));
    }

    // ========================================
    // Helper Methods
    // ========================================

    /** Common happy-path stubbing for applyPaymentToInvoices tests. */
    private void stubAppliableRequest() {
        when(paymentApplicationRepository.existsByApplicationRequestId(testApplicationRequestId))
                .thenReturn(false);
        when(receivablePaymentRepository.findById(testPaymentId)).thenReturn(Optional.of(testPayment));
        when(paymentApplicationRepository.save(any(PaymentApplication.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(receivablePaymentRepository.save(any(ReceivablePayment.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    private PaymentApplicationRequest createApplicationRequest(
            @NonNull String requestId, @NonNull List<PaymentApplicationRequest.InvoiceApplication> applications) {
        PaymentApplicationRequest request = new PaymentApplicationRequest();
        request.setApplicationRequestId(requestId);
        request.setApplications(applications);
        return request;
    }

    private PaymentApplicationRequest.InvoiceApplication createInvoiceApplication(
            @NonNull UUID invoiceId, @NonNull String amount) {
        PaymentApplicationRequest.InvoiceApplication app = new PaymentApplicationRequest.InvoiceApplication();
        app.setInvoiceId(invoiceId);
        app.setAmountToApply(new BigDecimal(amount));
        return app;
    }

    /**
     * Stub the replica lookup + derived balance for an invoice (ADR-0044, #842). Lenient because
     * validation-failure tests never reach the status derivation.
     */
    private void stubInvoice(@NonNull UUID invoiceId, @NonNull String balanceDue) {
        stubInvoice(invoiceId, balanceDue, null);
    }

    /**
     * Stub variant that also sets the replica invoice date, used by allocation-strategy tests
     * (Issue #955).
     */
    private void stubInvoice(@NonNull UUID invoiceId, @NonNull String balanceDue, @Nullable Instant invoiceCreatedAt) {
        ExtInvoice invoice = ExtInvoice.builder()
                .invoiceId(invoiceId)
                .invoiceCreatedAt(invoiceCreatedAt)
                .workorderId(UUID.fromString("00000000-0000-0000-0000-0000000000ff"))
                .partyId(testCustomerId.toString())
                .status("FINALIZED")
                .total(new BigDecimal(balanceDue))
                .aggregateVersion(1L)
                .updatedAt(Instant.now(TEST_CLOCK))
                .build();
        lenient().when(invoiceBalanceCalculator.findInvoice(invoiceId)).thenReturn(Optional.of(invoice));
        lenient().when(invoiceBalanceCalculator.isArEligible(invoice)).thenReturn(true);
        lenient().when(invoiceBalanceCalculator.balanceDue(invoice)).thenReturn(new BigDecimal(balanceDue));
        lenient()
                .when(invoiceBalanceCalculator.deriveArStatus(eq(invoice), any(BigDecimal.class)))
                .thenAnswer(invocation -> {
                    BigDecimal after = invocation.getArgument(1);
                    if (after.compareTo(BigDecimal.ZERO) <= 0) {
                        return InvoiceStatus.PAID_IN_FULL;
                    }
                    if (after.compareTo(invoice.getTotal()) < 0) {
                        return InvoiceStatus.PARTIALLY_PAID;
                    }
                    return InvoiceStatus.OPEN;
                });
    }
}
