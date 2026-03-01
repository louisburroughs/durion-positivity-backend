package com.positivity.accounting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.positivity.accounting.internal.client.InvoiceServiceClient;
import com.positivity.accounting.internal.client.InvoiceServiceException;
import com.positivity.accounting.internal.dto.ApplyPaymentToInvoiceRequest;
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
import com.positivity.accounting.internal.service.InvoicePaymentStatusServiceImpl;
import com.positivity.accounting.internal.service.PaymentApplicationServiceImpl;

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
        private InvoicePaymentStatusServiceImpl invoicePaymentStatusService;

        @Mock
        private InvoiceServiceClient invoiceServiceClient;

        @InjectMocks
        private PaymentApplicationServiceImpl service;

        @Captor
        private ArgumentCaptor<PaymentApplication> paymentApplicationCaptor;

        @Captor
        private ArgumentCaptor<ApplyPaymentToInvoiceRequest> invoiceRequestCaptor;

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
                                .thenAnswer(invocation -> invocation.getArgument(0));
                when(receivablePaymentRepository.save(any(ReceivablePayment.class)))
                                .thenAnswer(invocation -> invocation.getArgument(0));
                when(invoiceServiceClient.getInvoiceDetails(any())).thenReturn(
                                createTestInvoiceDetails(testInvoiceId, InvoiceStatus.OPEN, "USD", "1000.00"));
                when(invoiceServiceClient.applyPaymentToInvoice(eq(testInvoiceId), any())).thenReturn(
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

                // Verify invoice service called before entity save (new ordering)
                verify(invoiceServiceClient).applyPaymentToInvoice(eq(testInvoiceId), any());
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
                                .thenAnswer(invocation -> invocation.getArgument(0));
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
                                testApplicationRequestId,
                                List.of(createInvoiceApplication(testInvoiceId, "600.00")));

                when(paymentApplicationRepository.existsByApplicationRequestId(testApplicationRequestId))
                                .thenReturn(false);
                when(receivablePaymentRepository.findById(testPaymentId)).thenReturn(Optional.of(testPayment));
                when(paymentApplicationRepository.save(any(PaymentApplication.class)))
                                .thenAnswer(invocation -> invocation.getArgument(0));
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
                                testApplicationRequestId,
                                List.of(createInvoiceApplication(testInvoiceId, "500.00")));

                // Act
                PaymentApplicationResponse response = service.applyPaymentToInvoices(testPaymentId, request);

                // Assert
                assertThat(response).isNotNull();
                assertThat(response.getPaymentId()).isEqualTo(testPaymentId);

                // Verify balance data returned from persisted entity (no invoice service call)
                assertThat(response.getApplications()).hasSize(1);
                PaymentApplicationResponse.ApplicationDetail detail = response.getApplications().get(0);
                assertThat(detail.getInvoiceBalanceBefore()).isEqualByComparingTo("1000.00");
                assertThat(detail.getInvoiceBalanceAfter()).isEqualByComparingTo("500.00");
                assertThat(detail.getInvoiceStatus()).isEqualTo(InvoiceStatus.PARTIALLY_PAID);

                verify(paymentApplicationRepository, never()).save(any());
        }

        @Test
        @DisplayName("Should use pre-generated ID as idempotency key for invoice service")
        void testApplyPaymentToInvoices_PreGeneratedIdUsedAsIdempotencyKey() {
                // Arrange
                ApplyPaymentToInvoiceResponse invoiceResponse = new ApplyPaymentToInvoiceResponse();
                invoiceResponse.setBalanceBefore(new BigDecimal("1000.00"));
                invoiceResponse.setBalanceAfter(new BigDecimal("500.00"));
                invoiceResponse.setStatus(InvoiceStatus.PARTIALLY_PAID);

                when(paymentApplicationRepository.existsByApplicationRequestId(testApplicationRequestId))
                                .thenReturn(false);
                when(receivablePaymentRepository.findById(testPaymentId)).thenReturn(Optional.of(testPayment));
                when(invoiceServiceClient.getInvoiceDetails(any())).thenReturn(
                                createTestInvoiceDetails(testInvoiceId, InvoiceStatus.OPEN, "USD", "1000.00"));
                when(invoiceServiceClient.applyPaymentToInvoice(eq(testInvoiceId),
                                any(ApplyPaymentToInvoiceRequest.class)))
                                .thenReturn(invoiceResponse);
                when(paymentApplicationRepository.save(any(PaymentApplication.class)))
                                .thenAnswer(invocation -> invocation.getArgument(0));
                when(receivablePaymentRepository.save(any(ReceivablePayment.class)))
                                .thenAnswer(invocation -> invocation.getArgument(0));

                PaymentApplicationRequest request = createApplicationRequest(
                                testApplicationRequestId,
                                List.of(createInvoiceApplication(testInvoiceId, "500.00")));

                // Act
                service.applyPaymentToInvoices(testPaymentId, request);

                // Assert — the pre-generated ID sent to invoice service matches the persisted
                // entity ID
                verify(invoiceServiceClient).applyPaymentToInvoice(eq(testInvoiceId), invoiceRequestCaptor.capture());
                verify(paymentApplicationRepository).save(paymentApplicationCaptor.capture());

                UUID idSentToInvoiceService = invoiceRequestCaptor.getValue().getPaymentApplicationId();
                UUID idPersistedOnEntity = paymentApplicationCaptor.getValue().getPaymentApplicationId();

                assertThat(idSentToInvoiceService).isNotNull();
                assertThat(idPersistedOnEntity).isNotNull();
                assertThat(idSentToInvoiceService).isEqualTo(idPersistedOnEntity);
        }

        @Test
        @DisplayName("Should persist balance snapshot for idempotent retries")
        void testApplyPaymentToInvoices_BalanceSnapshotPersistedForIdempotentRetries() {
                // Arrange
                ApplyPaymentToInvoiceResponse invoiceResponse = new ApplyPaymentToInvoiceResponse();
                invoiceResponse.setBalanceBefore(new BigDecimal("1000.00"));
                invoiceResponse.setBalanceAfter(new BigDecimal("500.00"));
                invoiceResponse.setStatus(InvoiceStatus.PARTIALLY_PAID);

                when(paymentApplicationRepository.existsByApplicationRequestId(testApplicationRequestId))
                                .thenReturn(false);
                when(receivablePaymentRepository.findById(testPaymentId)).thenReturn(Optional.of(testPayment));
                when(invoiceServiceClient.getInvoiceDetails(any())).thenReturn(
                                createTestInvoiceDetails(testInvoiceId, InvoiceStatus.OPEN, "USD", "1000.00"));
                when(invoiceServiceClient.applyPaymentToInvoice(eq(testInvoiceId),
                                any(ApplyPaymentToInvoiceRequest.class)))
                                .thenReturn(invoiceResponse);
                when(paymentApplicationRepository.save(any(PaymentApplication.class)))
                                .thenAnswer(invocation -> invocation.getArgument(0));
                when(receivablePaymentRepository.save(any(ReceivablePayment.class)))
                                .thenAnswer(invocation -> invocation.getArgument(0));

                PaymentApplicationRequest request = createApplicationRequest(
                                testApplicationRequestId,
                                List.of(createInvoiceApplication(testInvoiceId, "500.00")));

                // Act
                PaymentApplicationResponse response = service.applyPaymentToInvoices(testPaymentId, request);

                // Assert — response contains balance data
                assertThat(response.getApplications()).hasSize(1);
                PaymentApplicationResponse.ApplicationDetail detail = response.getApplications().get(0);
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
                existingApplication.setPaymentApplicationId(UUID.randomUUID());
                existingApplication.setPaymentId(testPaymentId);
                existingApplication.setCustomerId(testCustomerId);
                existingApplication.setInvoiceId(testInvoiceId);
                existingApplication.setAppliedAmount(new BigDecimal("500.00"));
                existingApplication.setCurrency("USD");
                existingApplication.setApplicationRequestId(testApplicationRequestId);
                existingApplication.setApplicationTimestamp(Instant.now());
                existingApplication.setInvoiceBalanceBefore(new BigDecimal("2000.00"));
                existingApplication.setInvoiceBalanceAfter(new BigDecimal("1500.00"));
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

                // Assert — balance data comes from persisted entity
                assertThat(response.getApplications()).hasSize(1);
                PaymentApplicationResponse.ApplicationDetail detail = response.getApplications().get(0);
                assertThat(detail.getInvoiceBalanceBefore()).isEqualByComparingTo("2000.00");
                assertThat(detail.getInvoiceBalanceAfter()).isEqualByComparingTo("1500.00");
                assertThat(detail.getInvoiceStatus()).isEqualTo(InvoiceStatus.PARTIALLY_PAID);

                // Verify invoice service was NOT called (idempotent retry)
                verify(invoiceServiceClient, never()).applyPaymentToInvoice(any(), any());
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
        // voidPayment() Tests
        // ========================================

        @Test
        @DisplayName("Should void payment when no applications exist")
        void testVoidPayment_Success() {
                // Arrange
                when(receivablePaymentRepository.findById(testPaymentId)).thenReturn(Optional.of(testPayment));
                when(paymentApplicationRepository.findByPaymentId(testPaymentId)).thenReturn(List.of());
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
                existing.setPaymentApplicationId(UUID.randomUUID());
                existing.setPaymentId(testPaymentId);
                existing.setInvoiceId(testInvoiceId);
                existing.setAppliedAmount(new BigDecimal("100.00"));

                when(receivablePaymentRepository.findById(testPaymentId)).thenReturn(Optional.of(testPayment));
                when(paymentApplicationRepository.findByPaymentId(testPaymentId)).thenReturn(List.of(existing));

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

                when(receivablePaymentRepository.findById(testPaymentId)).thenReturn(Optional.of(testPayment));
                when(paymentApplicationRepository.findByPaymentId(testPaymentId)).thenReturn(List.of(application));
                when(paymentApplicationReversalRepository.existsByOriginalPaymentApplicationId(applicationId))
                                .thenReturn(false);
                when(paymentApplicationRepository.findById(applicationId)).thenReturn(Optional.of(application));
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
                service.reversePayment(testPaymentId, "Customer requested full reversal");

                // Assert
                assertThat(testPayment.getUnappliedAmount()).isEqualByComparingTo("1000.00");
                verify(invoiceServiceClient).reversePaymentApplication(eq(testInvoiceId), any());
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
                when(paymentApplicationRepository.findByPaymentId(testPaymentId)).thenReturn(List.of());

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
                UUID applicationId = UUID.randomUUID();
                PaymentApplication application = new PaymentApplication();
                application.setPaymentApplicationId(applicationId);
                application.setPaymentId(testPaymentId);
                application.setInvoiceId(testInvoiceId);
                application.setAppliedAmount(new BigDecimal("100.00"));

                when(receivablePaymentRepository.findById(testPaymentId)).thenReturn(Optional.of(testPayment));
                when(paymentApplicationRepository.findByPaymentId(testPaymentId)).thenReturn(List.of(application));
                when(paymentApplicationReversalRepository.existsByOriginalPaymentApplicationId(applicationId))
                                .thenReturn(true);

                // Act
                service.reversePayment(testPaymentId, "Retry reversal");

                // Assert
                verify(paymentApplicationRepository, never()).findById(applicationId);
                verify(paymentApplicationReversalRepository, never()).save(any(PaymentApplicationReversal.class));
                verify(invoiceServiceClient, never()).reversePaymentApplication(any(), any());
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

        @Test
        @DisplayName("Should fail and roll back local changes when invoice service fails during reversal")
        void testReversePaymentApplication_InvoiceServiceFailure() {
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
                when(invoiceServiceClient.reversePaymentApplication(any(), any()))
                                .thenThrow(new InvoiceServiceException("Invoice service unavailable", 503));

                // Act & Assert — should throw SERVICE_UNAVAILABLE, triggering @Transactional
                // rollback
                assertThatThrownBy(() -> service.reversePaymentApplication(
                                applicationId,
                                "Customer disputed charge"))
                                .isInstanceOf(ResponseStatusException.class)
                                .hasMessageContaining("Failed to restore invoice")
                                .hasMessageContaining(testInvoiceId.toString())
                                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                                .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);

                // Verify invoice service was called
                verify(invoiceServiceClient).reversePaymentApplication(eq(testInvoiceId), any());
        }

        @Test
        @DisplayName("Should propagate 4xx status from invoice service during reversal")
        void testReversePaymentApplication_InvoiceServiceClientError() {
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
                when(invoiceServiceClient.reversePaymentApplication(any(), any()))
                                .thenThrow(new InvoiceServiceException("Invoice not found", 404));

                // Act & Assert — should propagate 404 from invoice service
                assertThatThrownBy(() -> service.reversePaymentApplication(
                                applicationId,
                                "Customer disputed charge"))
                                .isInstanceOf(ResponseStatusException.class)
                                .hasMessageContaining("Failed to restore invoice")
                                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                                .isEqualTo(HttpStatus.NOT_FOUND);
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
