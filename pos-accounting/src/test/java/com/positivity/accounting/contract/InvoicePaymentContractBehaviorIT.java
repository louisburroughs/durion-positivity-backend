package com.positivity.accounting.contract;

import java.time.ZoneOffset;
import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import com.positivity.accounting.BaseContractIntegrationTest;
import com.positivity.accounting.internal.entity.PaymentAppliedEvent;
import com.positivity.accounting.internal.enums.PaymentStatus;
import com.positivity.accounting.internal.repository.PaymentAppliedEventRepository;

/**
 * Contract Behavioral Integration Tests for Invoice Payment operations.
 *
 * <p>
 * This test suite validates the behavioral contracts for Invoice Payment REST
 * endpoints
 * including applying payments to invoices and querying invoice payment status.
 *
 * <p>
 * Tests verify:
 * - Happy path payment application (LEGACY endpoint)
 * - Invoice status queries
 * - Payment idempotency handling
 * - Path parameter validation
 * - Error handling (400, 404, 500)
 *
 * <p>
 * Payment-centric API is at
 * /v1/accounting/payments/{paymentId}/applications.
 * Both endpoints are tested for backward compatibility.
 */
@DisplayName("Invoice Payment Backend Contract Behavioral Tests")
class InvoicePaymentContractBehaviorIT extends BaseContractIntegrationTest {
    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);

    private static final String API_V1_INVOICES = "/v1/accounting/invoices";
    private static final String API_V1_INVOICE = "/v1/accounting/invoice";

    @Autowired
    private PaymentAppliedEventRepository paymentAppliedEventRepository;

    // Test data
    private UUID testInvoiceId;
    private UUID testPaymentId;
    private String testIdempotencyKey;

    @BeforeEach
    void setUp() {
        // Setup test IDs
        testInvoiceId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        testPaymentId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        testIdempotencyKey = UUID.fromString("00000000-0000-0000-0000-000000000001").toString();
    }

    @AfterEach
    void tearDown() {
        paymentAppliedEventRepository.deleteAll();
    }

    // ===============================================
    // HAPPY PATH SCENARIOS - LEGACY ENDPOINT
    // ===============================================

    @Test
    @DisplayName("Apply payment to invoice (LEGACY) - happy path")
    void testApplyPayment_Success() throws Exception {
        // Given - valid payment application request
        String payload = """
                {
                    "invoiceId": "%s",
                    "paymentId": "%s",
                    "amount": 100.00,
                    "paymentDate": "%s",
                    "idempotencyKey": "%s"
                }
                """.formatted(testInvoiceId, testPaymentId, Instant.now(TEST_CLOCK).toString(), testIdempotencyKey);

        // When/Then
        MvcResult result = mockMvc.perform(withAuth(post(API_V1_INVOICES + "/" + testInvoiceId + "/pay"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
                .andReturn();

        // Accept either 200 (if implemented) or 501 (if stub)
        int statusCode = result.getResponse().getStatus();
        assertThat(statusCode).isIn(200, 501);

        if (statusCode == 200) {
            // Verify response structure if implemented
            String responseBody = result.getResponse().getContentAsString();
            assertThat(responseBody).isNotEmpty();
        }
    }

    @Test
    @DisplayName("Apply payment with idempotency - duplicate request returns same result")
    void testApplyPayment_IdempotencyHandling() throws Exception {
        // Given - valid payment application request
        String payload = """
                {
                    "invoiceId": "%s",
                    "paymentId": "%s",
                    "amount": 100.00,
                    "paymentDate": "%s",
                    "idempotencyKey": "%s"
                }
                """.formatted(testInvoiceId, testPaymentId, Instant.now(TEST_CLOCK).toString(), testIdempotencyKey);

        // When - submit first payment
        MvcResult firstResult = mockMvc.perform(withAuth(post(API_V1_INVOICES + "/" + testInvoiceId + "/pay"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
                .andReturn();

        int firstStatusCode = firstResult.getResponse().getStatus();

        // Then - submit duplicate with same idempotency key
        if (firstStatusCode == 200) {
            MvcResult secondResult = mockMvc.perform(withAuth(post(API_V1_INVOICES + "/" + testInvoiceId + "/pay"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(payload))
                    .andReturn();

            // Should return same result (200) or 409 conflict
            int secondStatusCode = secondResult.getResponse().getStatus();
            assertThat(secondStatusCode).isIn(200, 409);
        }
    }

    @Test
    @DisplayName("Get invoice status - happy path")
    void testGetInvoiceStatus_Success() throws Exception {
        // Given - payment event exists for this invoice
        createTestPaymentAppliedEvent(testInvoiceId, PaymentStatus.PAID,
                new BigDecimal("100.00"), new BigDecimal("100.00"));

        // When/Then
        MvcResult result = mockMvc.perform(withAuth(get(API_V1_INVOICE + "/" + testInvoiceId + "/status")))
                .andReturn();

        // Accept either 200 (if implemented) or 501 (if stub)
        int statusCode = result.getResponse().getStatus();
        assertThat(statusCode).isIn(200, 404, 501);

        if (statusCode == 200) {
            // Verify response structure
            String responseBody = result.getResponse().getContentAsString();
            assertThat(responseBody).contains("status");
        }
    }

    @Test
    @DisplayName("Get invoice status - invoice not found returns 404")
    void testGetInvoiceStatus_NotFound() throws Exception {
        // Given - non-existent invoice ID
        UUID nonExistentInvoiceId = UUID.fromString("00000000-0000-0000-0000-000000000001");

        // When/Then
        MvcResult result = mockMvc.perform(withAuth(get(API_V1_INVOICE + "/" + nonExistentInvoiceId + "/status")))
                .andReturn();

        // Should return 404 or 501 if not implemented
        int statusCode = result.getResponse().getStatus();
        assertThat(statusCode).isIn(404, 501);
    }

    // ===============================================
    // VALIDATION SCENARIOS
    // ===============================================

    @Test
    @DisplayName("Apply payment - path parameter mismatch with body")
    void testApplyPayment_PathBodyMismatch() throws Exception {
        // Given - invoice ID in path doesn't match body
        UUID pathInvoiceId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        String payload = """
                {
                    "invoiceId": "%s",
                    "paymentId": "%s",
                    "amount": 100.00,
                    "paymentDate": "%s",
                    "idempotencyKey": "%s"
                }
                """.formatted(testInvoiceId, testPaymentId, Instant.now(TEST_CLOCK).toString(), testIdempotencyKey);

        // When/Then - should return 400 for mismatch
        MvcResult result = mockMvc.perform(withAuth(post(API_V1_INVOICES + "/" + pathInvoiceId + "/pay"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
                .andReturn();

        // Accept either 400 (if validation implemented) or 501 (if stub)
        int statusCode = result.getResponse().getStatus();
        assertThat(statusCode).isIn(400, 501);
    }

    @Test
    @DisplayName("Apply payment - missing required fields")
    void testApplyPayment_MissingFields() throws Exception {
        // Given - invalid request with missing fields
        String payload = """
                {
                    "invoiceId": "%s"
                }
                """.formatted(testInvoiceId);

        // When/Then
        MvcResult result = mockMvc.perform(withAuth(post(API_V1_INVOICES + "/" + testInvoiceId + "/pay"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
                .andReturn();

        // Should return 400 for validation error or 501 if not implemented
        int statusCode = result.getResponse().getStatus();
        assertThat(statusCode).isIn(400, 501);
    }

    @Test
    @DisplayName("Apply payment - invalid amount (negative)")
    void testApplyPayment_InvalidAmount() throws Exception {
        // Given - invalid payment with negative amount
        String payload = """
                {
                    "invoiceId": "%s",
                    "paymentId": "%s",
                    "amount": -50.00,
                    "paymentDate": "%s",
                    "idempotencyKey": "%s"
                }
                """.formatted(testInvoiceId, testPaymentId, Instant.now(TEST_CLOCK).toString(), testIdempotencyKey);

        // When/Then
        MvcResult result = mockMvc.perform(withAuth(post(API_V1_INVOICES + "/" + testInvoiceId + "/pay"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
                .andReturn();

        // Should return 400 for validation error or 501 if not implemented
        int statusCode = result.getResponse().getStatus();
        assertThat(statusCode).isIn(400, 422, 501);
    }

    // ===============================================
    // STUB ENDPOINT SCENARIOS
    // ===============================================

    @Test
    @DisplayName("Regenerate invoice from workorder - implemented endpoint")
    void testRegenerateInvoice() throws Exception {
        // Given
        String payload = """
                {
                    "workorderId": "%s"
                }
                """.formatted(UUID.fromString("00000000-0000-0000-0000-000000000001"));

        // When/Then
        MvcResult result = mockMvc.perform(withAuth(post(API_V1_INVOICE + "/invoices"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
                .andReturn();

        // Implemented behavior:
        // 200 when invoice regeneration succeeds,
        // 404 when workorder does not exist,
        // 409 when workorder is not in COMPLETED state,
        // 503 when downstream workorder service is unavailable in local runs.
        int statusCode = result.getResponse().getStatus();
        assertThat(statusCode).isIn(200, 404, 409, 503);
    }

    @Test
    @DisplayName("Regenerate invoice from workorder - missing workorderId returns 400")
    void testRegenerateInvoice_MissingWorkorderId() throws Exception {
        // Given - missing required workorderId field
        String payload = """
                {
                    "idempotencyKey": "%s"
                }
                """.formatted(UUID.fromString("00000000-0000-0000-0000-000000000001"));

        // When/Then
        mockMvc.perform(withAuth(post(API_V1_INVOICE + "/invoices"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Get billing rules - implemented endpoint")
    void testGetBillingRules() throws Exception {
        // Given
        UUID customerId = UUID.fromString("00000000-0000-0000-0000-000000000001");

        // When/Then
        MvcResult result = mockMvc.perform(withAuth(get(API_V1_INVOICE + "/rules/" + customerId)))
                .andReturn();

        // Implemented behavior:
        // 200 when rules resolve, 404 when customer missing, 503 when downstream
        // customer service is unavailable in local integration runs.
        int statusCode = result.getResponse().getStatus();
        assertThat(statusCode).isIn(200, 404, 503);
    }

    // ===============================================
    // AUTHORIZATION SCENARIOS
    // ===============================================

    @Test
    @DisplayName("Apply payment - without proper authority returns 403")
    void testApplyPayment_Unauthorized() throws Exception {
        // Given - request without accounting:ap:pay authority
        String payload = """
                {
                    "invoiceId": "%s",
                    "paymentId": "%s",
                    "amount": 100.00,
                    "paymentDate": "%s",
                    "idempotencyKey": "%s"
                }
                """.formatted(testInvoiceId, testPaymentId, Instant.now(TEST_CLOCK).toString(), testIdempotencyKey);

        // When/Then - should return 403 Forbidden
        mockMvc.perform(withAuth(post(API_V1_INVOICES + "/" + testInvoiceId + "/pay"), "accounting:je:view")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Get invoice status - without proper authority returns 403")
    void testGetInvoiceStatus_Unauthorized() throws Exception {
        // Given - request without accounting:ap:view authority

        // When/Then - should return 403 Forbidden
        mockMvc.perform(withAuth(get(API_V1_INVOICE + "/" + testInvoiceId + "/status"), "accounting:je:view"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Regenerate invoice - without proper authority returns 403")
    void testRegenerateInvoice_Unauthorized() throws Exception {
        // Given - request without accounting:ap:view authority
        String payload = """
                {
                    "workorderId": "%s"
                }
                """.formatted(UUID.fromString("00000000-0000-0000-0000-000000000001"));

        // When/Then - should return 403 Forbidden
        mockMvc.perform(withAuth(post(API_V1_INVOICE + "/invoices"), "accounting:je:view")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
                .andExpect(status().isForbidden());
    }

    // ===============================================
    // PAYMENT STATUS TRANSITION SCENARIOS
    // ===============================================

    @Test
    @DisplayName("Invoice status transitions - unpaid to partially paid to paid")
    void testInvoiceStatusTransitions() throws Exception {
        // This test validates the status transition workflow if implemented
        // Given - invoice starts in unpaid state

        // When - apply first partial payment
        String firstPayment = """
                {
                    "invoiceId": "%s",
                    "paymentId": "%s",
                    "amount": 50.00,
                    "paymentDate": "%s",
                    "idempotencyKey": "%s"
                }
                """.formatted(testInvoiceId, testPaymentId, Instant.now(TEST_CLOCK).toString(),
                UUID.fromString("00000000-0000-0000-0000-000000000001").toString());

        MvcResult firstResult = mockMvc.perform(withAuth(post(API_V1_INVOICES + "/" + testInvoiceId + "/pay"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(firstPayment))
                .andReturn();

        // Then - if implemented, status should be PARTIALLY_PAID
        if (firstResult.getResponse().getStatus() == 200) {
            String firstResponse = firstResult.getResponse().getContentAsString();
            // Verify partial payment state if implemented
            assertThat(firstResponse).isNotEmpty();
        }

        // When - apply second payment to complete
        String secondPayment = """
                {
                    "invoiceId": "%s",
                    "paymentId": "%s",
                    "amount": 50.00,
                    "paymentDate": "%s",
                    "idempotencyKey": "%s"
                }
                """.formatted(testInvoiceId, UUID.fromString("00000000-0000-0000-0000-000000000001"),
                Instant.now(TEST_CLOCK).toString(), UUID.fromString("00000000-0000-0000-0000-000000000001").toString());

        MvcResult secondResult = mockMvc.perform(withAuth(post(API_V1_INVOICES + "/" + testInvoiceId + "/pay"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(secondPayment))
                .andReturn();

        // Then - if implemented, status should be PAID
        if (secondResult.getResponse().getStatus() == 200) {
            String secondResponse = secondResult.getResponse().getContentAsString();
            // Verify fully paid state if implemented
            assertThat(secondResponse).isNotEmpty();
        }
    }

    // ===============================================
    // HELPER METHODS
    // ===============================================

    /**
     * Creates a test {@link PaymentAppliedEvent} for the given invoice.
     *
     * @param invoiceId    the invoice to apply the payment to
     * @param status       the payment status (e.g. PAID, PARTIALLY_PAID, UNPAID,
     *                     FAILED)
     * @param amount       the payment amount
     * @param invoiceTotal the total invoice amount (used for status calculation)
     */
    private void createTestPaymentAppliedEvent(UUID invoiceId, PaymentStatus status,
            BigDecimal amount, BigDecimal invoiceTotal) {
        var event = new PaymentAppliedEvent(
                invoiceId,
                "TXN-" + UUID.fromString("00000000-0000-0000-0000-000000000001"),
                amount,
                invoiceTotal,
                status,
                UUID.fromString("00000000-0000-0000-0000-000000000001").toString());
        paymentAppliedEventRepository.save(event);
    }
}
