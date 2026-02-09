package com.positivity.accounting.integration;

import com.positivity.accounting.internal.client.InvoiceServiceClient;
import com.positivity.accounting.internal.dto.ApplyPaymentToInvoiceResponse;
import com.positivity.accounting.internal.dto.InvoiceDetails;
import com.positivity.accounting.internal.enums.InvoiceStatus;
import com.positivity.accounting.internal.dto.PaymentApplicationRequest;
import com.positivity.accounting.internal.dto.PaymentApplicationReversalRequest;
import com.positivity.accounting.internal.dto.ReversePaymentApplicationResponse;
import com.positivity.accounting.internal.entity.PaymentApplication;
import com.positivity.accounting.internal.entity.ReceivablePayment;
import com.positivity.accounting.internal.entity.ReceivablePayment.ReceivablePaymentStatus;
import com.positivity.accounting.internal.repository.PaymentApplicationRepository;
import com.positivity.accounting.internal.repository.ReceivablePaymentRepository;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for Payment Application REST API endpoints
 * 
 * Tests full request/response cycle with actual database and Spring Security.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("Payment Application Controller Integration Tests")
class PaymentApplicationControllerIntegrationTest {

        @Autowired
        private MockMvc mockMvc;

        @Autowired
        private ObjectMapper objectMapper;

        @Autowired
        private ReceivablePaymentRepository receivablePaymentRepository;

        @Autowired
        private PaymentApplicationRepository paymentApplicationRepository;

        @MockitoBean
        private InvoiceServiceClient invoiceServiceClient;

        private static final String API_V1 = "/v1/accounting";
        private static final String TEST_USER = "testuser";
        private static final String TEST_AUTHORITIES = String.join(",",
                        "accounting:payment:apply",
                        "accounting:payment:reverse",
                        "ACCOUNTING_ADMIN");

        private UUID testPaymentId;
        private UUID testCustomerId;
        private UUID testInvoice1Id;
        private UUID testInvoice2Id;

        @BeforeEach
        void setUp() {
                // Clean up test data
                paymentApplicationRepository.deleteAll();
                receivablePaymentRepository.deleteAll();

                // Initialize test IDs
                testPaymentId = UUID.randomUUID();
                testCustomerId = UUID.randomUUID();
                testInvoice1Id = UUID.randomUUID();
                testInvoice2Id = UUID.randomUUID();

                // Mock InvoiceServiceClient to prevent real HTTP calls
                when(invoiceServiceClient.getInvoiceDetails(any())).thenAnswer(invocation -> {
                        UUID invoiceId = invocation.getArgument(0);
                        return InvoiceDetails.builder()
                                        .invoiceId(invoiceId)
                                        .customerId(testCustomerId)
                                        .status(InvoiceStatus.OPEN)
                                        .currency("USD")
                                        .totalAmount(new BigDecimal("10000.00"))
                                        .balanceDue(new BigDecimal("10000.00"))
                                        .build();
                });
                when(invoiceServiceClient.applyPaymentToInvoice(any(), any())).thenAnswer(invocation -> {
                        UUID invoiceId = invocation.getArgument(0);
                        com.positivity.accounting.internal.dto.ApplyPaymentToInvoiceRequest req = invocation
                                        .getArgument(1);
                        BigDecimal balanceBefore = new BigDecimal("10000.00");
                        BigDecimal balanceAfter = balanceBefore.subtract(req.getAmountApplied());
                        return ApplyPaymentToInvoiceResponse.builder()
                                        .invoiceId(invoiceId)
                                        .status(balanceAfter.compareTo(BigDecimal.ZERO) == 0
                                                        ? InvoiceStatus.PAID_IN_FULL
                                                        : InvoiceStatus.PARTIALLY_PAID)
                                        .balanceBefore(balanceBefore)
                                        .balanceAfter(balanceAfter)
                                        .totalPaid(req.getAmountApplied())
                                        .totalAmount(balanceBefore)
                                        .build();
                });
                when(invoiceServiceClient.reversePaymentApplication(any(), any())).thenAnswer(invocation -> {
                        UUID invoiceId = invocation.getArgument(0);
                        com.positivity.accounting.internal.dto.ReversePaymentApplicationRequest req = invocation
                                        .getArgument(1);
                        return ReversePaymentApplicationResponse.builder()
                                        .invoiceId(invoiceId)
                                        .status(InvoiceStatus.OPEN)
                                        .balanceBefore(BigDecimal.ZERO)
                                        .balanceDue(req.getAmountToRestore())
                                        .build();
                });
        }

        /**
         * Adds gateway authentication headers to a request builder.
         */
        private MockHttpServletRequestBuilder withAuth(MockHttpServletRequestBuilder builder) {
                return builder
                                .header("X-User", TEST_USER)
                                .header("X-Authorities", TEST_AUTHORITIES);
        }

        // ========================================
        // POST /v1/accounting/payments/{paymentId}/applications
        // ========================================

        @Test
        @DisplayName("PA-001: Apply payment to single invoice successfully")
        void testApplyPayment_SingleInvoice_Success() throws Exception {
                // Arrange: Create a payment with funds
                createTestPayment(testPaymentId, testCustomerId, "1000.00");

                PaymentApplicationRequest request = new PaymentApplicationRequest();
                request.setApplicationRequestId(UUID.randomUUID().toString());
                request.setApplications(List.of(
                                createInvoiceApplication(testInvoice1Id, "500.00")));

                // Act: POST to apply payment endpoint
                MvcResult result = mockMvc
                                .perform(withAuth(post(API_V1 + "/payments/" + testPaymentId + "/applications"))
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(objectMapper.writeValueAsString(request)))
                                .andDo(print())
                                .andExpect(status().isCreated())
                                .andExpect(jsonPath("$.paymentId").value(testPaymentId.toString()))
                                .andExpect(jsonPath("$.customerId").value(testCustomerId.toString()))
                                .andExpect(jsonPath("$.appliedAmount").value(500.00))
                                .andExpect(jsonPath("$.remainingAmount").value(500.00))
                                .andExpect(jsonPath("$.applications").isArray())
                                .andExpect(jsonPath("$.applications.length()").value(1))
                                .andExpect(jsonPath("$.applications[0].invoiceId").value(testInvoice1Id.toString()))
                                .andExpect(jsonPath("$.applications[0].appliedAmount").value(500.00))
                                .andExpect(jsonPath("$.customerCredit").doesNotExist())
                                .andReturn();

                // Assert: Verify database state
                List<PaymentApplication> applications = paymentApplicationRepository.findByPaymentId(testPaymentId);
                assertThat(applications).hasSize(1);
                assertThat(applications.get(0).getAppliedAmount()).isEqualByComparingTo("500.00");
                assertThat(applications.get(0).getInvoiceId()).isEqualTo(testInvoice1Id);

                ReceivablePayment payment = receivablePaymentRepository.findById(testPaymentId).orElseThrow();
                assertThat(payment.getUnappliedAmount()).isEqualByComparingTo("500.00");
        }

        @Test
        @DisplayName("PA-002: Apply payment to multiple invoices successfully")
        void testApplyPayment_MultipleInvoices_Success() throws Exception {
                // Arrange
                createTestPayment(testPaymentId, testCustomerId, "1000.00");

                PaymentApplicationRequest request = new PaymentApplicationRequest();
                request.setApplicationRequestId(UUID.randomUUID().toString());
                request.setApplications(List.of(
                                createInvoiceApplication(testInvoice1Id, "300.00"),
                                createInvoiceApplication(testInvoice2Id, "400.00")));

                // Act
                mockMvc.perform(withAuth(post(API_V1 + "/payments/" + testPaymentId + "/applications"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                                .andDo(print())
                                .andExpect(status().isCreated())
                                .andExpect(jsonPath("$.appliedAmount").value(700.00))
                                .andExpect(jsonPath("$.remainingAmount").value(300.00))
                                .andExpect(jsonPath("$.applications.length()").value(2));

                // Assert
                List<PaymentApplication> applications = paymentApplicationRepository.findByPaymentId(testPaymentId);
                assertThat(applications).hasSize(2);

                ReceivablePayment payment = receivablePaymentRepository.findById(testPaymentId).orElseThrow();
                assertThat(payment.getUnappliedAmount()).isEqualByComparingTo("300.00");
                assertThat(payment.getStatus()).isEqualTo(ReceivablePaymentStatus.AVAILABLE);
        }

        @Test
        @DisplayName("PA-003: Apply payment partially to single invoice")
        void testApplyPayment_PartialApplication() throws Exception {
                // Arrange
                createTestPayment(testPaymentId, testCustomerId, "1000.00");

                PaymentApplicationRequest request = new PaymentApplicationRequest();
                request.setApplicationRequestId(UUID.randomUUID().toString());
                request.setApplications(List.of(
                                createInvoiceApplication(testInvoice1Id, "600.00")));

                // Act
                mockMvc.perform(withAuth(post(API_V1 + "/payments/" + testPaymentId + "/applications"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                                .andDo(print())
                                .andExpect(status().isCreated())
                                .andExpect(jsonPath("$.appliedAmount").value(600.00))
                                .andExpect(jsonPath("$.remainingAmount").value(400.00))
                                .andExpect(jsonPath("$.customerCredit").doesNotExist());

                // Assert
                ReceivablePayment payment = receivablePaymentRepository.findById(testPaymentId).orElseThrow();
                assertThat(payment.getUnappliedAmount()).isEqualByComparingTo("400.00");
                assertThat(payment.getStatus()).isEqualTo(ReceivablePaymentStatus.AVAILABLE);
        }

        @Test
        @DisplayName("PA-004: Enforce idempotency with duplicate applicationRequestId")
        void testApplyPayment_Idempotent() throws Exception {
                // Arrange
                createTestPayment(testPaymentId, testCustomerId, "1000.00");

                String idempotencyKey = UUID.randomUUID().toString();
                PaymentApplicationRequest request = new PaymentApplicationRequest();
                request.setApplicationRequestId(idempotencyKey);
                request.setApplications(List.of(
                                createInvoiceApplication(testInvoice1Id, "500.00")));

                // Act: First request
                mockMvc.perform(withAuth(post(API_V1 + "/payments/" + testPaymentId + "/applications"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                                .andExpect(status().isCreated());

                // Act: Second request with same idempotency key
                mockMvc.perform(withAuth(post(API_V1 + "/payments/" + testPaymentId + "/applications"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                                .andDo(print())
                                .andExpect(status().isCreated()) // Still returns 201, but doesn't duplicate
                                .andExpect(jsonPath("$.paymentId").value(testPaymentId.toString()))
                                .andExpect(jsonPath("$.appliedAmount").value(500.00));

                // Assert: Only one application created
                List<PaymentApplication> applications = paymentApplicationRepository.findByPaymentId(testPaymentId);
                assertThat(applications).hasSize(1);

                ReceivablePayment payment = receivablePaymentRepository.findById(testPaymentId).orElseThrow();
                assertThat(payment.getUnappliedAmount()).isEqualByComparingTo("500.00"); // Not applied twice
        }

        @Test
        @DisplayName("PA-004b: Enforce idempotency with duplicate multi-invoice applicationRequestId")
        void testApplyPayment_MultiInvoice_Idempotent() throws Exception {
                // Arrange
                createTestPayment(testPaymentId, testCustomerId, "1000.00");

                String idempotencyKey = UUID.randomUUID().toString();
                PaymentApplicationRequest request = new PaymentApplicationRequest();
                request.setApplicationRequestId(idempotencyKey);
                request.setApplications(List.of(
                                createInvoiceApplication(testInvoice1Id, "300.00"),
                                createInvoiceApplication(testInvoice2Id, "400.00")));

                // Act: First request
                mockMvc.perform(withAuth(post(API_V1 + "/payments/" + testPaymentId + "/applications"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                                .andExpect(status().isCreated())
                                .andExpect(jsonPath("$.appliedAmount").value(700.00))
                                .andExpect(jsonPath("$.applications.length()").value(2));

                // Act: Second request with same idempotency key (retry scenario)
                mockMvc.perform(withAuth(post(API_V1 + "/payments/" + testPaymentId + "/applications"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                                .andDo(print())
                                .andExpect(status().isCreated()) // Still returns 201, but doesn't duplicate
                                .andExpect(jsonPath("$.paymentId").value(testPaymentId.toString()))
                                .andExpect(jsonPath("$.appliedAmount").value(700.00)) // Sum of both applications
                                .andExpect(jsonPath("$.applications.length()").value(2)); // All applications returned

                // Assert: Only two applications created (not duplicated)
                List<PaymentApplication> applications = paymentApplicationRepository.findByPaymentId(testPaymentId);
                assertThat(applications).hasSize(2);

                // Assert: Total applied is correct (not doubled)
                BigDecimal totalApplied = applications.stream()
                                .map(PaymentApplication::getAppliedAmount)
                                .reduce(BigDecimal.ZERO, BigDecimal::add);
                assertThat(totalApplied).isEqualByComparingTo("700.00");

                ReceivablePayment payment = receivablePaymentRepository.findById(testPaymentId).orElseThrow();
                assertThat(payment.getUnappliedAmount()).isEqualByComparingTo("300.00"); // Not applied twice
        }

        @Test
        @DisplayName("PA-005: Fail when payment not found")
        void testApplyPayment_PaymentNotFound() throws Exception {
                // Arrange
                UUID nonExistentPaymentId = UUID.randomUUID();
                PaymentApplicationRequest request = new PaymentApplicationRequest();
                request.setApplicationRequestId(UUID.randomUUID().toString());
                request.setApplications(List.of(
                                createInvoiceApplication(testInvoice1Id, "500.00")));

                // Act & Assert
                mockMvc.perform(withAuth(post(API_V1 + "/payments/" + nonExistentPaymentId + "/applications"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                                .andDo(print())
                                .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("PA-006: Fail when insufficient funds")
        void testApplyPayment_InsufficientFunds() throws Exception {
                // Arrange
                createTestPayment(testPaymentId, testCustomerId, "1000.00");

                PaymentApplicationRequest request = new PaymentApplicationRequest();
                request.setApplicationRequestId(UUID.randomUUID().toString());
                request.setApplications(List.of(
                                createInvoiceApplication(testInvoice1Id, "1500.00") // More than available
                ));

                // Act & Assert
                mockMvc.perform(withAuth(post(API_V1 + "/payments/" + testPaymentId + "/applications"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                                .andDo(print())
                                .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("PA-007: Fail when payment not AVAILABLE")
        void testApplyPayment_PaymentNotAvailable() throws Exception {
                // Arrange: Create payment with FULLY_APPLIED status
                ReceivablePayment payment = new ReceivablePayment();
                payment.setPaymentId(testPaymentId);
                payment.setCustomerId(testCustomerId);
                payment.setTotalAmount(new BigDecimal("1000.00"));
                payment.setUnappliedAmount(BigDecimal.ZERO);
                payment.setCurrency("USD");
                payment.setStatus(ReceivablePaymentStatus.FULLY_APPLIED);
                payment.setClearedAt(Instant.now());
                payment.setSourceEventId(UUID.randomUUID());
                payment.setCreatedAt(Instant.now());
                receivablePaymentRepository.save(payment);

                PaymentApplicationRequest request = new PaymentApplicationRequest();
                request.setApplicationRequestId(UUID.randomUUID().toString());
                request.setApplications(List.of(
                                createInvoiceApplication(testInvoice1Id, "500.00")));

                // Act & Assert
                mockMvc.perform(withAuth(post(API_V1 + "/payments/" + testPaymentId + "/applications"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                                .andDo(print())
                                .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("PA-008: Fail validation when missing applicationRequestId")
        void testApplyPayment_MissingIdempotencyKey() throws Exception {
                // Arrange
                createTestPayment(testPaymentId, testCustomerId, "1000.00");

                PaymentApplicationRequest request = new PaymentApplicationRequest();
                // Missing applicationRequestId
                request.setApplications(List.of(
                                createInvoiceApplication(testInvoice1Id, "500.00")));

                // Act & Assert
                mockMvc.perform(withAuth(post(API_V1 + "/payments/" + testPaymentId + "/applications"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                                .andDo(print())
                                .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("PA-009: Fail validation when empty applications list")
        void testApplyPayment_EmptyApplicationsList() throws Exception {
                // Arrange
                createTestPayment(testPaymentId, testCustomerId, "1000.00");

                PaymentApplicationRequest request = new PaymentApplicationRequest();
                request.setApplicationRequestId(UUID.randomUUID().toString());
                request.setApplications(List.of()); // Empty list

                // Act & Assert
                mockMvc.perform(withAuth(post(API_V1 + "/payments/" + testPaymentId + "/applications"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                                .andDo(print())
                                .andExpect(status().isBadRequest());
        }

        // ========================================
        // POST /v1/accounting/payment-applications/{applicationId}/reverse
        // ========================================

        @Test
        @DisplayName("PAR-001: Reverse payment application successfully")
        void testReversePaymentApplication_Success() throws Exception {
                // Arrange: Create payment and apply it to an invoice
                createTestPayment(testPaymentId, testCustomerId, "1000.00");

                PaymentApplication application = new PaymentApplication();
                application.setPaymentApplicationId(UUID.randomUUID());
                application.setPaymentId(testPaymentId);
                application.setCustomerId(testCustomerId);
                application.setInvoiceId(testInvoice1Id);
                application.setAppliedAmount(new BigDecimal("500.00"));
                application.setCurrency("USD");
                application.setApplicationRequestId(UUID.randomUUID().toString());
                application.setApplicationTimestamp(Instant.now());
                application.setCreatedBy("testuser");
                application.setCreatedAt(Instant.now());
                application = paymentApplicationRepository.save(application);

                // Update payment unappliedAmount to reflect application
                ReceivablePayment payment = receivablePaymentRepository.findById(testPaymentId).orElseThrow();
                payment.setUnappliedAmount(new BigDecimal("500.00"));
                receivablePaymentRepository.save(payment);

                PaymentApplicationReversalRequest request = new PaymentApplicationReversalRequest();
                request.setReason("Customer disputed the charge and requested refund");

                // Act
                mockMvc.perform(
                                withAuth(post(API_V1 + "/payment-applications/" + application.getPaymentApplicationId()
                                                + "/reverse"))
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(objectMapper.writeValueAsString(request)))
                                .andDo(print())
                                .andExpect(status().isNoContent());

                // Assert: Verify payment unappliedAmount restored
                payment = receivablePaymentRepository.findById(testPaymentId).orElseThrow();
                assertThat(payment.getUnappliedAmount()).isEqualByComparingTo("1000.00");

                // Note: Application reversal is tracked via PaymentApplicationReversal entity,
                // not a flag
        }

        @Test
        @DisplayName("PAR-002: Fail when application not found")
        void testReversePaymentApplication_NotFound() throws Exception {
                // Arrange
                UUID nonExistentApplicationId = UUID.randomUUID();
                PaymentApplicationReversalRequest request = new PaymentApplicationReversalRequest();
                request.setReason("Test reason for reversal");

                // Act & Assert
                mockMvc.perform(withAuth(
                                post(API_V1 + "/payment-applications/" + nonExistentApplicationId + "/reverse"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                                .andDo(print())
                                .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("PAR-003: Fail validation when reason too short")
        void testReversePaymentApplication_ReasonTooShort() throws Exception {
                // Arrange
                createTestPayment(testPaymentId, testCustomerId, "1000.00");

                // Apply payment first to create valid PaymentApplication
                PaymentApplicationRequest applyRequest = new PaymentApplicationRequest();
                applyRequest.setApplicationRequestId(UUID.randomUUID().toString());
                applyRequest.setApplications(List.of(createInvoiceApplication(testInvoice1Id, "500.00")));

                mockMvc.perform(withAuth(post(API_V1 + "/payments/" + testPaymentId + "/applications"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(applyRequest)))
                                .andExpect(status().isCreated());

                // Find the created application
                List<PaymentApplication> applications = paymentApplicationRepository.findAll();
                PaymentApplication application = applications.get(0);

                PaymentApplicationReversalRequest request = new PaymentApplicationReversalRequest();
                request.setReason("Short"); // Too short (min 10 chars)

                // Act & Assert
                mockMvc.perform(
                                withAuth(post(API_V1 + "/payment-applications/" + application.getPaymentApplicationId()
                                                + "/reverse"))
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(objectMapper.writeValueAsString(request)))
                                .andDo(print())
                                .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("PAR-004: Fail when reason missing")
        void testReversePaymentApplication_MissingReason() throws Exception {
                // Arrange
                createTestPayment(testPaymentId, testCustomerId, "1000.00");

                // Apply payment first to create valid PaymentApplication
                PaymentApplicationRequest applyRequest = new PaymentApplicationRequest();
                applyRequest.setApplicationRequestId(UUID.randomUUID().toString());
                applyRequest.setApplications(List.of(createInvoiceApplication(testInvoice1Id, "500.00")));

                mockMvc.perform(withAuth(post(API_V1 + "/payments/" + testPaymentId + "/applications"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(applyRequest)))
                                .andExpect(status().isCreated());

                // Find the created application
                List<PaymentApplication> applications = paymentApplicationRepository.findAll();
                PaymentApplication application = applications.get(0);

                PaymentApplicationReversalRequest request = new PaymentApplicationReversalRequest();
                // No reason set

                // Act & Assert
                mockMvc.perform(
                                withAuth(post(API_V1 + "/payment-applications/" + application.getPaymentApplicationId()
                                                + "/reverse"))
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(objectMapper.writeValueAsString(request)))
                                .andDo(print())
                                .andExpect(status().isBadRequest());
        }

        // ========================================
        // Helper Methods
        // ========================================

        private void createTestPayment(UUID paymentId, UUID customerId, String amount) {
                ReceivablePayment payment = new ReceivablePayment();
                payment.setPaymentId(paymentId);
                payment.setCustomerId(customerId);
                payment.setTotalAmount(new BigDecimal(amount));
                payment.setUnappliedAmount(new BigDecimal(amount));
                payment.setCurrency("USD");
                payment.setStatus(ReceivablePaymentStatus.AVAILABLE);
                payment.setClearedAt(Instant.now());
                payment.setSourceEventId(UUID.randomUUID());
                payment.setCreatedAt(Instant.now());
                receivablePaymentRepository.save(payment);
        }

        private PaymentApplicationRequest.InvoiceApplication createInvoiceApplication(UUID invoiceId, String amount) {
                PaymentApplicationRequest.InvoiceApplication app = new PaymentApplicationRequest.InvoiceApplication();
                app.setInvoiceId(invoiceId);
                app.setAmountToApply(new BigDecimal(amount));
                return app;
        }
}
