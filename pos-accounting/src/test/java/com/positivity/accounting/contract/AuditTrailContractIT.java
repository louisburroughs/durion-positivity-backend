package com.positivity.accounting.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import tools.jackson.databind.JsonNode;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import com.positivity.accounting.BaseContractIntegrationTest;
import com.positivity.accounting.internal.audit.entity.OverridePolicyThreshold;
import com.positivity.accounting.internal.audit.entity.RefundPolicyConfig;
import com.positivity.accounting.internal.audit.repository.AuditTrailEntryRepository;
import com.positivity.accounting.internal.audit.repository.OverridePolicyThresholdRepository;
import com.positivity.accounting.internal.audit.repository.RefundPolicyConfigRepository;

/**
 * Acceptance criteria contract tests for Story #1: Audit Trail — Track Price
 * Overrides, Refunds, and Cancellations.
 *
 * <p>
 * Each test maps to a single AC and runs end-to-end through the real service
 * stack. Policy fixtures are seeded in {@code @BeforeEach} so that
 * authorization logic is exercised with realistic thresholds.
 *
 * <p>
 * All AC tests (AC1–AC8) are GREEN.
 *
 * Issue: #1
 */
@DisplayName("Story #1 — Audit Trail AC Contract Tests")
class AuditTrailContractIT extends BaseContractIntegrationTest {

    // -----------------------------------------------------------------------
    // Constants
    // -----------------------------------------------------------------------

    private static final String AUDIT_BASE = "/v1/accounting/audit";

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-03-10T12:00:00Z"), ZoneOffset.UTC);

    /** Role used for happy-path overrides; bound to the "generous" policy. */
    private static final String ROLE_MANAGER = "STORE_MANAGER";

    /** Role used for threshold-exceeded tests; bound to the "tight" policy. */
    private static final String ROLE_CASHIER = "CASHIER";

    private static final UUID ORDER_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID LINE_ITEM_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID INVOICE_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID PAYMENT_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000004");

    // -----------------------------------------------------------------------
    // Repository injections for seeding / cleanup
    // -----------------------------------------------------------------------

    @Autowired
    private AuditTrailEntryRepository auditRepository;

    @Autowired
    private OverridePolicyThresholdRepository overridePolicyRepository;

    @Autowired
    private RefundPolicyConfigRepository refundPolicyRepository;

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    /**
     * Seeds policy fixtures required by AC1-AC7 service-layer validation.
     * Cleans all audit entries so each test starts from an empty ledger.
     */
    @BeforeEach
    void seedPoliciesAndCleanAudit() {
        auditRepository.deleteAll();
        overridePolicyRepository.deleteAll();
        refundPolicyRepository.deleteAll();

        // STORE_MANAGER — generous threshold: $200 absolute, 50 % off
        overridePolicyRepository.save(
                OverridePolicyThreshold.builder()
                        .role(ROLE_MANAGER)
                        .maxAbsoluteAmount(new BigDecimal("200.00"))
                        .maxPercentOff(new BigDecimal("50.00"))
                        .effectiveDate(Instant.now(FIXED_CLOCK).minusSeconds(3600))
                        .version("v-test")
                        .active(true)
                        .build());

        // CASHIER — tight threshold: $10 absolute, 5 % off
        overridePolicyRepository.save(
                OverridePolicyThreshold.builder()
                        .role(ROLE_CASHIER)
                        .maxAbsoluteAmount(new BigDecimal("10.00"))
                        .maxPercentOff(new BigDecimal("5.00"))
                        .effectiveDate(Instant.now(FIXED_CLOCK).minusSeconds(3600))
                        .version("v-test")
                        .active(true)
                        .build());

        // Active refund policy required by refund/cancellation endpoints
        refundPolicyRepository.save(
                RefundPolicyConfig.builder()
                        .requiresSeparateAuthorization(false)
                        .settledPaymentHandling("CREDIT_MEMO")
                        .unsettledPaymentHandling("REVERSAL")
                        .version("v-test")
                        .active(true)
                        .build());
    }

    @AfterEach
    void cleanUp() {
        auditRepository.deleteAll();
        overridePolicyRepository.deleteAll();
        refundPolicyRepository.deleteAll();
    }

    // -----------------------------------------------------------------------
    // AC1 — threshold exceeded: rejected, no entry persisted
    //
    // GREEN: service correctly returns 403 with message
    // "Authorization threshold exceeded" as required by AC1.
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("AC1: price override exceeding role threshold → 403 'Authorization threshold exceeded', no audit entry")
    void ac1_priceOverrideExceedingRoleThreshold_rejectedWithExactReasonAndNoEntry()
            throws Exception {
        // Arrange — CASHIER has max $10 abs; discount = $400
        String payload = """
                {
                    "orderId": "%s",
                    "lineItemId": "%s",
                    "originalPrice": 500.00,
                    "adjustedPrice": 100.00,
                    "actorRole": "%s",
                    "reason": "Unapproved discount"
                }
                """.formatted(ORDER_ID, LINE_ITEM_ID, ROLE_CASHIER);

        // Act
        mockMvc.perform(withAuth(post(AUDIT_BASE + "/price-override"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                // Assert — 403 with the AC-prescribed rejection reason
                .andExpect(status().isForbidden())
                // Issue #1 AC1: exact message required by story acceptance criteria
                .andExpect(jsonPath("$.error").value("Authorization threshold exceeded"));

        // AC1: no audit entry must be persisted on rejection
        assertThat(auditRepository.count())
                .as("no audit entry should be created when override is rejected")
                .isEqualTo(0);
    }

    // -----------------------------------------------------------------------
    // AC2 — forbidden category (below cost): rejected, no entry persisted
    //
    // GREEN: PriceOverrideRequest exposes categoryCode; the service correctly
    // detects BELOW_COST and rejects with 403 "Pricing below cost not permitted".
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("AC2: below-cost price override → 403 'Pricing below cost not permitted', no audit entry")
    void ac2_belowCostPriceOverride_rejectedAsForbiddenCategoryWithNoEntry()
            throws Exception {
        // Arrange — amounts within CASHIER threshold so only the category check
        // would reject; categoryCode = "BELOW_COST" is passed to service via DTO
        String payload = """
                {
                    "orderId": "%s",
                    "lineItemId": "%s",
                    "originalPrice": 100.00,
                    "adjustedPrice": 98.00,
                    "actorRole": "%s",
                    "reason": "Below cost override attempt",
                    "categoryCode": "BELOW_COST"
                }
                """.formatted(ORDER_ID, LINE_ITEM_ID, ROLE_CASHIER);

        // Act
        mockMvc.perform(withAuth(post(AUDIT_BASE + "/price-override"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                // Issue #1 AC2: expect 403 with category-denial reason
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Pricing below cost not permitted"));

        // No entry should be persisted on forbidden-category rejection
        assertThat(auditRepository.count())
                .as("no audit entry should be created for forbidden category override")
                .isEqualTo(0);
    }

    // -----------------------------------------------------------------------
    // AC3 — recorded override: GET audit trail contains all required fields
    //
    // EXPECTED GREEN: service correctly populates all fields including actorId
    // resolved from SecurityContext (ADR-0018) and policyValidationResult=APPROVED.
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("AC3: recorded price override → GET audit trail includes actorId, timestamp, exceptionType, prices, policyValidationResult=APPROVED")
    void ac3_recordedPriceOverride_auditTrailContainsAllRequiredFields() throws Exception {
        // Arrange — small discount well within STORE_MANAGER threshold
        String payload = """
                {
                    "orderId": "%s",
                    "lineItemId": "%s",
                    "originalPrice": 100.00,
                    "adjustedPrice": 90.00,
                    "actorRole": "%s",
                    "reason": "Customer loyalty applied"
                }
                """.formatted(ORDER_ID, LINE_ITEM_ID, ROLE_MANAGER);

        // Act — record the override; capture response for all AC3 assertions
        MvcResult create = mockMvc.perform(withAuth(post(AUDIT_BASE + "/price-override"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.exceptionType").value("PRICE_OVERRIDE"))
                .andExpect(jsonPath("$.orderId").value(ORDER_ID.toString()))
                .andExpect(jsonPath("$.lineItemId").value(LINE_ITEM_ID.toString()))
                .andExpect(jsonPath("$.originalPrice").value(100.00))
                .andExpect(jsonPath("$.adjustedPrice").value(90.00))
                .andExpect(jsonPath("$.policyValidationResult").value("APPROVED"))
                .andExpect(jsonPath("$.actorId").value(TEST_USER))
                .andReturn();

        String createBody = create.getResponse().getContentAsString();

        // AC3: actor MUST be resolved from SecurityContext, not from request payload
        assertThat(createBody)
                .as("actorId should be resolved from the authenticated principal (ADR-0018)")
                .contains("\"actorId\":\"" + TEST_USER + "\"");

        // AC3: timestamp must be present (immutable, set server-side)
        assertThat(createBody)
                .as("timestamp must be present in the audit entry")
                .contains("\"timestamp\":");
    }

    // -----------------------------------------------------------------------
    // AC4 — query by orderId at story-defined path /by-order/{orderId}
    //
    // GREEN: controller correctly maps to /by-order/{orderId} and returns
    // audit entries sorted by ascending timestamp as required by AC4.
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("AC4: GET /by-order/{orderId} → returns all exceptions sorted by timestamp")
    void ac4_queryByOrderId_returnsEntriesSortedByTimestampAtCorrectPath()
            throws Exception {
        // Arrange — record two overrides so the query has entries to return
        String payload1 = """
                {
                    "orderId": "%s",
                    "lineItemId": "%s",
                    "originalPrice": 100.00,
                    "adjustedPrice": 95.00,
                    "actorRole": "%s",
                    "reason": "First override"
                }
                """.formatted(ORDER_ID, LINE_ITEM_ID, ROLE_MANAGER);

        String payload2 = """
                {
                    "orderId": "%s",
                    "lineItemId": "%s",
                    "originalPrice": 100.00,
                    "adjustedPrice": 90.00,
                    "actorRole": "%s",
                    "reason": "Second override"
                }
                """.formatted(ORDER_ID, LINE_ITEM_ID, ROLE_MANAGER);

        mockMvc.perform(withAuth(post(AUDIT_BASE + "/price-override"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload1))
                .andExpect(status().isCreated());

        mockMvc.perform(withAuth(post(AUDIT_BASE + "/price-override"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload2))
                .andExpect(status().isCreated());

        // AC4: story mandates the by-order path with sorted results
        MvcResult queryResult = mockMvc.perform(withAuth(get(AUDIT_BASE + "/by-order/" + ORDER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(2))
                .andReturn();

        // Issue #1 AC4: entries must be returned in ascending timestamp order
        JsonNode results = objectMapper.readTree(queryResult.getResponse().getContentAsString());
        String ts0 = results.get(0).get("timestamp").asText();
        String ts1 = results.get(1).get("timestamp").asText();
        assertThat(ts0)
                .as("audit entries should be sorted by ascending timestamp: results[0] <= results[1]")
                .isLessThanOrEqualTo(ts1);
    }

    // -----------------------------------------------------------------------
    // AC5 — settled payment refund: refundMethod must not be REVERSAL
    //
    // EXPECTED GREEN: RefundAuthorizationServiceImpl correctly returns
    // CREDIT_MEMO (or CASH_REFUND) for SETTLED + CREDIT_MEMO requests.
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("AC5: settled payment refund → refundMethod is CREDIT_MEMO or CASH_REFUND, never REVERSAL/VOID")
    void ac5_settledPaymentRefund_refundMethodIsNotReversal() throws Exception {
        // Arrange
        String payload = """
                {
                    "invoiceId": "%s",
                    "paymentId": "%s",
                    "refundType": "CREDIT_MEMO",
                    "refundAmount": 50.00,
                    "originalPaymentStatus": "SETTLED",
                    "actorRole": "%s",
                    "reason": "Damaged goods returned"
                }
                """.formatted(INVOICE_ID, PAYMENT_ID, ROLE_MANAGER);

        // Act & Assert
        MvcResult result = mockMvc.perform(withAuth(post(AUDIT_BASE + "/refund"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.exceptionType").value("REFUND"))
                .andExpect(jsonPath("$.invoiceId").value(INVOICE_ID.toString()))
                .andReturn();

        String body = result.getResponse().getContentAsString();

        // AC5: refundMethod must be CREDIT_MEMO or CASH_REFUND, not VOID/CHARGEBACK
        assertThat(body)
                .as("settled payment refund must use CREDIT_MEMO or CASH_REFUND method")
                .satisfiesAnyOf(
                        b -> assertThat(b).contains("\"refundMethod\":\"CREDIT_MEMO\""),
                        b -> assertThat(b).contains("\"refundMethod\":\"CASH_REFUND\""));

        assertThat(body)
                .as("settled payment refund must not use REVERSAL method")
                .doesNotContain("\"refundMethod\":\"REVERSAL\"");
    }

    // -----------------------------------------------------------------------
    // AC6 — cancellation: beforeSnapshot, afterSnapshot, partialPaymentInfo
    //
    // EXPECTED GREEN: service persists all three snapshot fields.
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("AC6: cancellation → audit entry captures beforeSnapshot, afterSnapshot, partial_payment_info")
    void ac6_cancellation_capturesAllSnapshotFields() throws Exception {
        // Arrange
        String beforeSnapshot = "{\"status\":\"PENDING\",\"total\":150.00}";
        String afterSnapshot  = "{\"status\":\"CANCELLED\",\"total\":0.00}";
        String partialInfo    = "{\"amountPaid\":50.00,\"outstanding\":100.00}";

        String payload = """
                {
                    "orderId": "%s",
                    "cancellationType": "ORDER_CANCELLED",
                    "beforeSnapshot": %s,
                    "afterSnapshot": %s,
                    "partialPaymentInfo": %s,
                    "actorRole": "%s",
                    "reason": "Customer requested cancellation"
                }
                """.formatted(
                ORDER_ID,
                "\"" + beforeSnapshot.replace("\"", "\\\"") + "\"",
                "\"" + afterSnapshot.replace("\"", "\\\"") + "\"",
                "\"" + partialInfo.replace("\"", "\\\"") + "\"",
                ROLE_MANAGER);

        // Act & Assert
        mockMvc.perform(withAuth(post(AUDIT_BASE + "/cancellation"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.exceptionType").value("CANCELLATION"))
                .andExpect(jsonPath("$.orderId").value(ORDER_ID.toString()))
                // AC6: all snapshot fields must be present
                .andExpect(jsonPath("$.beforeSnapshot").isNotEmpty())
                .andExpect(jsonPath("$.afterSnapshot").isNotEmpty())
                .andExpect(jsonPath("$.partialPaymentInfo").isNotEmpty());
    }

    // -----------------------------------------------------------------------
    // AC7 — events include accountingIntent, accountingStatus=PENDING_POSTING,
    //       sourceEventId, sourceDocumentId
    //
    // EXPECTED GREEN: service sets all of these on the AuditTrailEntry.
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("AC7: audit entry includes accountingIntent, accountingStatus=PENDING_POSTING, sourceEventId, sourceDocumentId")
    void ac7_auditEntry_includesAccountingFields() throws Exception {
        // Arrange
        String payload = """
                {
                    "orderId": "%s",
                    "lineItemId": "%s",
                    "originalPrice": 100.00,
                    "adjustedPrice": 85.00,
                    "actorRole": "%s",
                    "reason": "Manager approved discount"
                }
                """.formatted(ORDER_ID, LINE_ITEM_ID, ROLE_MANAGER);

        // Act & Assert
        mockMvc.perform(withAuth(post(AUDIT_BASE + "/price-override"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                // AC7: accountingStatus must be PENDING_POSTING at creation time
                .andExpect(jsonPath("$.accountingStatus").value("PENDING_POSTING"))
                // AC7: accountingIntent must be present
                .andExpect(jsonPath("$.accountingIntent").isNotEmpty())
                // AC7: traceability fields must be populated
                .andExpect(jsonPath("$.sourceEventId").isNotEmpty())
                .andExpect(jsonPath("$.sourceDocumentId").isNotEmpty());
    }

    // -----------------------------------------------------------------------
    // AC8 — audit entry MUST NOT contain glEntryId or postingTimestamp
    //
    // EXPECTED GREEN: AuditTrailResponse DTO does not define these fields;
    // they will simply be absent from the serialized JSON.
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("AC8: audit entry response does not contain glEntryId or postingTimestamp")
    void ac8_auditEntry_doesNotContainGlEntryIdOrPostingTimestamp() throws Exception {
        // Arrange — use a cancellation so no override policy is strictly required
        String payload = """
                {
                    "orderId": "%s",
                    "cancellationType": "ORDER_CANCELLED",
                    "beforeSnapshot": "{\\"status\\":\\"ACTIVE\\"}",
                    "afterSnapshot": "{\\"status\\":\\"CANCELLED\\"}",
                    "actorRole": "%s",
                    "reason": "Voided by manager"
                }
                """.formatted(ORDER_ID, ROLE_MANAGER);

        MvcResult result = mockMvc.perform(withAuth(post(AUDIT_BASE + "/cancellation"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andReturn();

        String body = result.getResponse().getContentAsString();

        // AC8: neither GL integration field should leak into the audit response
        assertThat(body)
                .as("glEntryId must not appear in the audit trail response")
                .doesNotContain("glEntryId");

        assertThat(body)
                .as("postingTimestamp must not appear in the audit trail response")
                .doesNotContain("postingTimestamp");
    }

    // -----------------------------------------------------------------------
    // AC6b — POST /audit/refund without authority header → 403 Forbidden
    //
    // GREEN: security filter rejects requests that carry X-User but no
    // X-Authorities header, per ADR-0017.
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("AC6b: POST /audit/refund without authority header → 401 Unauthorized")
    void ac6b_refundWithoutAuthority_returnsUnauthorized() throws Exception {
        String payload = """
                {
                    "invoiceId": "%s",
                    "paymentId": "%s",
                    "refundType": "CREDIT_MEMO",
                    "refundAmount": 50.00,
                    "originalPaymentStatus": "SETTLED",
                    "actorRole": "%s",
                    "reason": "Unauthorized refund attempt"
                }
                """.formatted(INVOICE_ID, PAYMENT_ID, ROLE_MANAGER);

        // Issue #1 AC6b: X-User present but no X-Authorities → 401.
        // GatewayAuthoritiesFilter clears the security context when the
        // X-Authorities header is absent, leaving an unauthenticated request.
        mockMvc.perform(post(AUDIT_BASE + "/refund")
                        .header("X-User", TEST_USER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isUnauthorized());
    }

    // -----------------------------------------------------------------------
    // AC8b — POST /audit/cancellation without authority header → 403 Forbidden
    //
    // GREEN: security filter rejects requests that carry X-User but no
    // X-Authorities header, per ADR-0017.
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("AC8b: POST /audit/cancellation without authority header → 401 Unauthorized")
    void ac8b_cancellationWithoutAuthority_returnsUnauthorized() throws Exception {
        String payload = """
                {
                    "orderId": "%s",
                    "cancellationType": "ORDER_CANCELLED",
                    "beforeSnapshot": "{\\"status\\":\\"PENDING\\"}",
                    "afterSnapshot": "{\\"status\\":\\"CANCELLED\\"}",
                    "partialPaymentInfo": null,
                    "actorRole": "%s",
                    "reason": "Unauthorized cancellation attempt"
                }
                """.formatted(ORDER_ID, ROLE_MANAGER);

        // Issue #1 AC8b: X-User present but no X-Authorities → 401.
        // GatewayAuthoritiesFilter clears the security context when the
        // X-Authorities header is absent, leaving an unauthenticated request.
        mockMvc.perform(post(AUDIT_BASE + "/cancellation")
                        .header("X-User", TEST_USER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isUnauthorized());
    }
}
