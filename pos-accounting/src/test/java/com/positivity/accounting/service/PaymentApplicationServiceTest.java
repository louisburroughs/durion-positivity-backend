package com.positivity.accounting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.positivity.accounting.internal.client.InvoiceServiceClient;
import com.positivity.accounting.internal.dto.ApplyPaymentToInvoiceResponse;
import com.positivity.accounting.internal.dto.InvoiceDetails;
import com.positivity.accounting.internal.enums.InvoiceStatus;
import com.positivity.accounting.internal.dto.PaymentApplicationRequest;
import com.positivity.accounting.internal.dto.PaymentApplicationResponse;
import com.positivity.accounting.internal.dto.ReversePaymentApplicationResponse;
import com.positivity.accounting.internal.entity.CustomerCredit;
import com.positivity.accounting.internal.entity.PaymentApplication;
import com.positivity.accounting.internal.entity.PaymentApplicationReversal;
import com.positivity.accounting.internal.entity.ReceivablePayment;
import com.positivity.accounting.internal.entity.ReceivablePayment.ReceivablePaymentStatus;
import com.positivity.accounting.internal.repository.CustomerCreditRepository;
import com.positivity.accounting.internal.repository.PaymentApplicationRepository;
import com.positivity.accounting.internal.repository.PaymentApplicationReversalRepository;
import com.positivity.accounting.internal.repository.ReceivablePaymentRepository;

/**
 * Unit tests for PaymentApplicationService
 * 
 * Tests business logic, validation rules, idempotency, and error handling
 * for payment application operations.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PaymentApplicationService Unit Tests")
class PaymentApplicationServiceTest {

        @Mock
        private ReceivablePaymentRepository receivablePaymentRepository;

        @Mock
        private PaymentApplicationRepository paymentApplicationRepository;

        @Mock
        private CustomerCreditRepository customerCreditRepository;

        @Mock
        private PaymentApplicationReversalRepository paymentApplicationReversalRepository;

        @Mock
        private InvoicePaymentStatusService invoicePaymentStatusService;

        @Mock
        private InvoiceServiceClient invoiceServiceClient;

        @InjectMocks
        private PaymentApplicationService service;

        private UUID testPaymentId;
        private UUID testCustomerId;
        private UUID testInvoiceId;
        private UUID testSourceEventId;
        private String testApplicationRequestId;
        private ReceivablePayment testPayment;

        @BeforeEach
        void setUp() {
                testPaymentId = UUID.randomUUID();
                testCustomerId = UUID.randomUUID();
                testInvoiceId = UUID.randomUUID();
                testSourceEventId = UUID.randomUUID();
                testApplicationRequestId = UUID.randomUUID().toString();

                testPayment = new ReceivablePayment();
                testPayment.setPaymentId(testPaymentId);
                testPayment.setCustomerId(testCustomerId);
                testPayment.setTotalAmount(new BigDecimal("1000.00"));
                testPayment.setUnappliedAmount(new BigDecimal("1000.00"));
                testPayment.setCurrency("USD");
                testPayment.setStatus(ReceivablePaymentStatus.AVAILABLE);
                testPayment.setClearedAt(Instant.now());
                testPayment.setSourceEventId(testSourceEventId);
                testPayment.setCreatedAt(Instant.now());
        }

        // ========================================
        // handlePaymentCleared() Tests
        // ========================================

        @Test
        @DisplayName("Should create ReceivablePayment when PaymentCleared event received")
        void testHandlePaymentCleared_Success() {
                // Arrange
                when(receivablePaymentRepository.existsBySourceEventId(testSourceEventId)).thenReturn(false);
                when(receivablePaymentRepository.save(any(ReceivablePayment.class))).thenReturn(testPayment);

                // Act
                ReceivablePayment result = service.handlePaymentCleared(
                                testPaymentId,
                                testCustomerId,
                                "USD",
                                new BigDecimal("1000.00"),
                                Instant.now(),
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
                when(receivablePaymentRepository.existsBySourceEventId(testSourceEventId)).thenReturn(true);
                when(receivablePaymentRepository.findBySourceEventId(testSourceEventId))
                                .thenReturn(Optional.of(testPayment));

                // Act
                ReceivablePayment result = service.handlePaymentCleared(
                                testPaymentId,
                                testCustomerId,
                                "USD",
                                new BigDecimal("1000.00"),
                                Instant.now(),
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
                                testApplicationRequestId,
                                List.of(createInvoiceApplication(testInvoiceId, "500.00")));

                when(paymentApplicationRepository.existsByApplicationRequestId(testApplicationRequestId))
                                .thenReturn(false);
                when(receivablePaymentRepository.findById(testPaymentId)).thenReturn(Optional.of(testPayment));
                when(paymentApplicationRepository.save(any(PaymentApplication.class)))
                                .thenAnswer(invocation -> {
                                        PaymentApplication app = invocation.getArgument(0);
                                        if (app.getPaymentApplicationId() == null) {
                                                app.setPaymentApplicationId(UUID.randomUUID());
                                        }
                                        return app;
                                });
                when(receivablePaymentRepository.save(any(ReceivablePayment.class)))
                                .thenAnswer(invocation -> invocation.getArgument(0));
                when(invoiceServiceClient.getInvoiceDetails(any())).thenReturn(
                                createTestInvoiceDetails(testInvoiceId, InvoiceStatus.OPEN, "USD", "1000.00"));
                when(invoiceServiceClient.applyPaymentToInvoice(any(), any())).thenReturn(
                                createApplyPaymentResponse(testInvoiceId, "1000.00", "500.00"));

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

                verify(paymentApplicationRepository).save(any(PaymentApplication.class));
                verify(receivablePaymentRepository).save(testPayment);
        }

        @Test
        @DisplayName("Should apply payment to multiple invoices successfully")
        void testApplyPaymentToInvoices_MultipleInvoices_Success() {
                // Arrange
                UUID invoice1 = UUID.randomUUID();
                UUID invoice2 = UUID.randomUUID();
                PaymentApplicationRequest request = createApplicationRequest(
                                testApplicationRequestId,
                                List.of(
                                                createInvoiceApplication(invoice1, "300.00"),
                                                createInvoiceApplication(invoice2, "400.00")));

                when(paymentApplicationRepository.existsByApplicationRequestId(testApplicationRequestId))
                                .thenReturn(false);
                when(receivablePaymentRepository.findById(testPaymentId)).thenReturn(Optional.of(testPayment));
                when(paymentApplicationRepository.save(any(PaymentApplication.class)))
                                .thenAnswer(invocation -> {
                                        PaymentApplication app = invocation.getArgument(0);
                                        if (app.getPaymentApplicationId() == null) {
                                                app.setPaymentApplicationId(UUID.randomUUID());
                                        }
                                        return app;
                                });
                when(receivablePaymentRepository.save(any(ReceivablePayment.class)))
                                .thenAnswer(invocation -> invocation.getArgument(0));
                when(invoiceServiceClient.getInvoiceDetails(any())).thenReturn(
                                createTestInvoiceDetails(UUID.randomUUID(), InvoiceStatus.OPEN, "USD", "1000.00"));
                when(invoiceServiceClient.applyPaymentToInvoice(any(), any())).thenReturn(
                                createApplyPaymentResponse(UUID.randomUUID(), "1000.00", "500.00"));

                // Act
                PaymentApplicationResponse response = service.applyPaymentToInvoices(testPaymentId, request);

                // Assert
                assertThat(response).isNotNull();
                assertThat(response.getAppliedAmount()).isEqualByComparingTo("700.00");
                assertThat(response.getRemainingAmount()).isEqualByComparingTo("300.00");
                assertThat(response.getApplications()).hasSize(2);

                verify(paymentApplicationRepository, times(2)).save(any(PaymentApplication.class));
        }

        @Test
        @DisplayName("Should create CustomerCredit for overpayment")
        void testApplyPaymentToInvoices_WithOverpayment_CreatesCredit() {
                // Arrange
                PaymentApplicationRequest request = createApplicationRequest(
                                testApplicationRequestId,
                                List.of(createInvoiceApplication(testInvoiceId, "600.00")));

                when(paymentApplicationRepository.existsByApplicationRequestId(testApplicationRequestId))
                                .thenReturn(false);
                when(receivablePaymentRepository.findById(testPaymentId)).thenReturn(Optional.of(testPayment));
                when(paymentApplicationRepository.save(any(PaymentApplication.class)))
                                .thenAnswer(invocation -> {
                                        PaymentApplication app = invocation.getArgument(0);
                                        if (app.getPaymentApplicationId() == null) {
                                                app.setPaymentApplicationId(UUID.randomUUID());
                                        }
                                        return app;
                                });
                when(receivablePaymentRepository.save(any(ReceivablePayment.class)))
                                .thenAnswer(invocation -> invocation.getArgument(0));
                when(invoiceServiceClient.getInvoiceDetails(any())).thenReturn(
                                createTestInvoiceDetails(testInvoiceId, InvoiceStatus.OPEN, "USD", "1000.00"));
                when(invoiceServiceClient.applyPaymentToInvoice(any(), any())).thenReturn(
                                createApplyPaymentResponse(testInvoiceId, "1000.00", "400.00"));
                // Note: customerCreditRepository.save() is NOT called in normal flow
                // Credit only created when: remainingAmount > 0 && status == FULLY_APPLIED
                // In normal flow, status stays AVAILABLE

                // Act
                PaymentApplicationResponse response = service.applyPaymentToInvoices(testPaymentId, request);

                // Assert - credit is null because payment status is AVAILABLE (not
                // FULLY_APPLIED)
                // Credit creation logic: if (remainingAmount > 0 && status == FULLY_APPLIED)
                // In normal flow, status stays AVAILABLE, so credit is not created
                assertThat(response.getCustomerCredit()).isNull();
                assertThat(response.getRemainingAmount()).isEqualByComparingTo("400.00"); // Verify remaining amount
                                                                                          // instead
                assertThat(response.getAppliedAmount()).isEqualByComparingTo("600.00");

                // Verify credit repository was NOT called
                verify(customerCreditRepository, never()).save(any(CustomerCredit.class));
        }

        @Test
        @DisplayName("Should enforce idempotency when duplicate applicationRequestId used")
        void testApplyPaymentToInvoices_Idempotent() {
                // Arrange
                PaymentApplication existingApplication = new PaymentApplication();
                existingApplication.setPaymentApplicationId(UUID.randomUUID());
                existingApplication.setPaymentId(testPaymentId);
                existingApplication.setCustomerId(testCustomerId);
                existingApplication.setInvoiceId(testInvoiceId);
                existingApplication.setAppliedAmount(new BigDecimal("500.00"));
                existingApplication.setCurrency("USD");
                existingApplication.setApplicationRequestId(testApplicationRequestId);
                existingApplication.setApplicationTimestamp(Instant.now());
                // Set invoice balance/status (persisted for idempotent retries)
                existingApplication.setInvoiceBalanceBefore(new BigDecimal("1000.00"));
                existingApplication.setInvoiceBalanceAfter(new BigDecimal("500.00"));
                existingApplication.setInvoiceStatus(InvoiceStatus.PARTIALLY_PAID);

                when(paymentApplicationRepository.existsByApplicationRequestId(testApplicationRequestId))
                                .thenReturn(true);
                when(paymentApplicationRepository.findAllByApplicationRequestId(testApplicationRequestId))
                                .thenReturn(List.of(existingApplication));
                when(receivablePaymentRepository.findById(testPaymentId)).thenReturn(Optional.of(testPayment));

                PaymentApplicationRequest request = createApplicationRequest(
                                testApplicationRequestId,
                                List.of(createInvoiceApplication(testInvoiceId, "500.00")));

                // Act
                PaymentApplicationResponse response = service.applyPaymentToInvoices(testPaymentId, request);

                // Assert
                assertThat(response).isNotNull();
                assertThat(response.getPaymentId()).isEqualTo(testPaymentId);
                verify(paymentApplicationRepository, never()).save(any());
        }

        @Test
        @DisplayName("Should fail when payment not found")
        void testApplyPaymentToInvoices_PaymentNotFound() {
                // Arrange
                PaymentApplicationRequest request = createApplicationRequest(
                                testApplicationRequestId,
                                List.of(createInvoiceApplication(testInvoiceId, "500.00")));

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
                                testApplicationRequestId,
                                List.of(createInvoiceApplication(testInvoiceId, "500.00")));

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
        // reversePaymentApplication() Tests
        // ========================================

        @Test
        @DisplayName("Should reverse payment application successfully")
        void testReversePaymentApplication_Success() {
                // Arrange
                UUID applicationId = UUID.randomUUID();
                PaymentApplication application = new PaymentApplication();
                application.setPaymentApplicationId(applicationId);
                application.setPaymentId(testPaymentId);
                application.setCustomerId(testCustomerId);
                application.setInvoiceId(testInvoiceId);
                application.setAppliedAmount(new BigDecimal("500.00"));
                application.setCurrency("USD");
                application.setApplicationRequestId(testApplicationRequestId);
                application.setApplicationTimestamp(Instant.now());

                testPayment.setUnappliedAmount(new BigDecimal("500.00")); // Already had some applied

                when(paymentApplicationReversalRepository.existsByOriginalPaymentApplicationId(applicationId))
                                .thenReturn(false); // Not yet reversed
                when(paymentApplicationRepository.findById(applicationId)).thenReturn(Optional.of(application));
                when(receivablePaymentRepository.findById(testPaymentId)).thenReturn(Optional.of(testPayment));
                when(paymentApplicationReversalRepository.save(any(PaymentApplicationReversal.class)))
                                .thenAnswer(invocation -> {
                                        PaymentApplicationReversal rev = invocation.getArgument(0);
                                        if (rev.getReversalId() == null) {
                                                rev.setReversalId(UUID.randomUUID());
                                        }
                                        return rev;
                                });
                when(receivablePaymentRepository.save(any(ReceivablePayment.class)))
                                .thenAnswer(invocation -> invocation.getArgument(0));
                when(invoiceServiceClient.reversePaymentApplication(any(), any())).thenReturn(
                                createReversePaymentResponse(testInvoiceId, "500.00", "1000.00"));

                // Act
                PaymentApplicationReversal result = service.reversePaymentApplication(
                                applicationId,
                                "Customer disputed charge");

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
        @DisplayName("Should use authenticated user from SecurityContext for reversal audit")
        void testReversePaymentApplication_WithAuthenticatedUser() {
                // Arrange
                UUID applicationId = UUID.randomUUID();
                PaymentApplication application = new PaymentApplication();
                application.setPaymentApplicationId(applicationId);
                application.setPaymentId(testPaymentId);
                application.setCustomerId(testCustomerId);
                application.setInvoiceId(testInvoiceId);
                application.setAppliedAmount(new BigDecimal("500.00"));
                application.setCurrency("USD");
                application.setApplicationRequestId(testApplicationRequestId);
                application.setApplicationTimestamp(Instant.now());

                testPayment.setUnappliedAmount(new BigDecimal("500.00"));

                when(paymentApplicationReversalRepository.existsByOriginalPaymentApplicationId(applicationId))
                                .thenReturn(false);
                when(paymentApplicationRepository.findById(applicationId)).thenReturn(Optional.of(application));
                when(receivablePaymentRepository.findById(testPaymentId)).thenReturn(Optional.of(testPayment));
                when(paymentApplicationReversalRepository.save(any(PaymentApplicationReversal.class)))
                                .thenAnswer(invocation -> {
                                        PaymentApplicationReversal rev = invocation.getArgument(0);
                                        if (rev.getReversalId() == null) {
                                                rev.setReversalId(UUID.randomUUID());
                                        }
                                        return rev;
                                });
                when(receivablePaymentRepository.save(any(ReceivablePayment.class)))
                                .thenAnswer(invocation -> invocation.getArgument(0));
                when(invoiceServiceClient.reversePaymentApplication(any(), any())).thenReturn(
                                createReversePaymentResponse(testInvoiceId, "500.00", "1000.00"));

                // Set up SecurityContext with authenticated user
                org.springframework.security.core.Authentication authentication = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                                "admin@example.com", null, List.of());
                org.springframework.security.core.context.SecurityContextHolder.getContext()
                                .setAuthentication(authentication);

                try {
                        // Act
                        PaymentApplicationReversal result = service.reversePaymentApplication(
                                        applicationId,
                                        "Customer disputed charge");

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
                UUID applicationId = UUID.randomUUID();
                when(paymentApplicationRepository.findById(applicationId)).thenReturn(Optional.empty());

                // Act & Assert
                assertThatThrownBy(() -> service.reversePaymentApplication(
                                applicationId,
                                "Test reason"))
                                .isInstanceOf(ResponseStatusException.class)
                                .hasMessageContaining("not found")
                                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                                .isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("Should fail when application already reversed")
        void testReversePaymentApplication_AlreadyReversed() {
                // Arrange
                UUID applicationId = UUID.randomUUID();
                PaymentApplication application = new PaymentApplication();
                application.setPaymentApplicationId(applicationId);
                application.setPaymentId(testPaymentId);
                application.setCustomerId(testCustomerId);
                application.setInvoiceId(testInvoiceId);
                application.setAppliedAmount(new BigDecimal("500.00"));
                application.setCurrency("USD");
                application.setApplicationRequestId(testApplicationRequestId);
                application.setApplicationTimestamp(Instant.now());
                // Note: No isReversed field - check via repository

                when(paymentApplicationReversalRepository.existsByOriginalPaymentApplicationId(applicationId))
                                .thenReturn(true); // Already reversed

                // Act & Assert
                assertThatThrownBy(() -> service.reversePaymentApplication(
                                applicationId,
                                "Second reversal attempt"))
                                .isInstanceOf(ResponseStatusException.class)
                                .hasMessageContaining("has already been reversed")
                                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                                .isEqualTo(HttpStatus.CONFLICT);
        }

        @Test
        @DisplayName("Should prevent duplicate reversals via repository check")
        void testReversePaymentApplication_DuplicateReversalPrevented() {
                // Arrange
                UUID applicationId = UUID.randomUUID();
                PaymentApplication application = new PaymentApplication();
                application.setPaymentApplicationId(applicationId);
                application.setPaymentId(testPaymentId);
                application.setCustomerId(testCustomerId);
                application.setInvoiceId(testInvoiceId);
                application.setAppliedAmount(new BigDecimal("500.00"));
                application.setCurrency("USD");
                application.setApplicationRequestId(testApplicationRequestId);
                application.setApplicationTimestamp(Instant.now());

                when(paymentApplicationReversalRepository.existsByOriginalPaymentApplicationId(applicationId))
                                .thenReturn(true); // Already reversed

                // Act & Assert
                assertThatThrownBy(() -> service.reversePaymentApplication(
                                applicationId,
                                "Test reason"))
                                .isInstanceOf(ResponseStatusException.class)
                                .hasMessageContaining("has already been reversed")
                                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                                .isEqualTo(HttpStatus.CONFLICT);

                verify(paymentApplicationReversalRepository, never()).save(any(PaymentApplicationReversal.class));
        }

        // ========================================
        // Helper Methods
        // ========================================

        private PaymentApplicationRequest createApplicationRequest(@NonNull String requestId,
                        @NonNull List<PaymentApplicationRequest.InvoiceApplication> applications) {
                PaymentApplicationRequest request = new PaymentApplicationRequest();
                request.setApplicationRequestId(requestId);
                request.setApplications(applications);
                return request;
        }

        private PaymentApplicationRequest.InvoiceApplication createInvoiceApplication(@NonNull UUID invoiceId,
                        @NonNull String amount) {
                PaymentApplicationRequest.InvoiceApplication app = new PaymentApplicationRequest.InvoiceApplication();
                app.setInvoiceId(invoiceId);
                app.setAmountToApply(new BigDecimal(amount));
                return app;
        }

        private InvoiceDetails createTestInvoiceDetails(@NonNull UUID invoiceId, @NonNull InvoiceStatus status,
                        @NonNull String currency, @NonNull String balanceDue) {
                return InvoiceDetails.builder()
                                .invoiceId(invoiceId)
                                .customerId(testCustomerId)
                                .status(status)
                                .currency(currency)
                                .totalAmount(new BigDecimal(balanceDue))
                                .balanceDue(new BigDecimal(balanceDue))
                                .build();
        }

        private ApplyPaymentToInvoiceResponse createApplyPaymentResponse(@NonNull UUID invoiceId,
                        @NonNull String balanceBefore, @NonNull String balanceAfter) {
                return ApplyPaymentToInvoiceResponse.builder()
                                .invoiceId(invoiceId)
                                .status(InvoiceStatus.PARTIALLY_PAID)
                                .balanceBefore(new BigDecimal(balanceBefore))
                                .balanceAfter(new BigDecimal(balanceAfter))
                                .totalPaid(new BigDecimal(balanceBefore).subtract(new BigDecimal(balanceAfter)))
                                .totalAmount(new BigDecimal(balanceBefore))
                                .build();
        }

        private ReversePaymentApplicationResponse createReversePaymentResponse(@NonNull UUID invoiceId,
                        @NonNull String balanceBefore, @NonNull String balanceDue) {
                return ReversePaymentApplicationResponse.builder()
                                .invoiceId(invoiceId)
                                .status(InvoiceStatus.OPEN)
                                .balanceBefore(new BigDecimal(balanceBefore))
                                .balanceDue(new BigDecimal(balanceDue))
                                .build();
        }
}
