package com.positivity.workorder.contract;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

import com.positivity.workorder.internal.entity.Estimate;
import com.positivity.workorder.internal.entity.EstimateItem;
import com.positivity.workorder.internal.entity.EstimateItemType;
import com.positivity.workorder.internal.enums.ApprovalStatus;
import com.positivity.workorder.internal.enums.EstimateStatus;
import com.positivity.workorder.internal.repository.EstimateItemRepository;
import com.positivity.workorder.internal.repository.EstimateRepository;
import com.positivity.workorder.support.BaseContractIntegrationTest;
import io.restassured.http.ContentType;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

/**
 * Contract behavior integration tests for the workorder approval endpoint
 * ({@code POST /v1/workorders/{workorderId}/approval}).
 *
 * <p>
 * Added by issue #1753. The endpoint previously caught {@code IllegalStateException}
 * in the controller and answered a bodiless 400, and nothing asserted that at all —
 * approveWorkorder had no invalid-state contract test, so the defect was invisible.
 * These tests pin the refusal statuses <em>and</em> the {@code ApiError} envelope,
 * because an assertion on the status alone would have passed against the empty body
 * that was the actual bug.
 * </p>
 *
 * Tests cover:
 * - Approving a DRAFT workorder (happy path)
 * - Re-approving an already-APPROVED workorder: 409, invalid lifecycle transition
 * - customerId that is not the workorder's own customer: 400, payload validation
 */
@DisplayName("Workorder Approval Contract Behavior Tests (#1753)")
@Import(ContractTestConfiguration.class)
class WorkorderApprovalContractBehaviorIT extends BaseContractIntegrationTest {

    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);

    @Autowired
    private EstimateRepository estimateRepository;

    @Autowired
    private EstimateItemRepository estimateItemRepository;

    private UUID testCustomerId;
    private UUID testLocationId;
    private UUID testVehicleId;

    @Test
    @DisplayName("WA-001: Approve a DRAFT workorder")
    void testApproveWorkorder_Draft() {
        UUID workorderId = seedDraftWorkorder();

        givenWithGatewayAuth()
                .contentType(ContentType.JSON)
                .body(approvalPayload(testCustomerId))
                .when()
                .post("/v1/workorders/{workorderId}/approval", workorderId)
                .then()
                .statusCode(200)
                .body("status", equalTo("APPROVED"))
                .log()
                .ifValidationFails();
    }

    @Test
    @DisplayName("WA-002: Reject approval - workorder is no longer in DRAFT")
    void testApproveWorkorder_InvalidState() {
        UUID workorderId = seedDraftWorkorder();

        // First approval succeeds, leaving the workorder APPROVED.
        givenWithGatewayAuth()
                .contentType(ContentType.JSON)
                .body(approvalPayload(testCustomerId))
                .when()
                .post("/v1/workorders/{workorderId}/approval", workorderId)
                .then()
                .statusCode(200);

        // The second is an invalid lifecycle transition: 409 per ADR-0017 §2, carrying a
        // full ApiError. The body assertions are the point of this test — the defect in
        // #1753 was an empty body behind a status that looked plausible.
        assertCorrelationIdEchoed(givenWithGatewayAuth()
                .contentType(ContentType.JSON)
                .body(approvalPayload(testCustomerId))
                .when()
                .post("/v1/workorders/{workorderId}/approval", workorderId)
                .then()
                .statusCode(409)
                .body("code", equalTo("CONFLICT"))
                .body("message", notNullValue())
                .log()
                .ifValidationFails()
                .extract());
    }

    @Test
    @DisplayName("WA-003: Reject approval - customerId is not the workorder's customer")
    void testApproveWorkorder_CustomerMismatch() {
        UUID workorderId = seedDraftWorkorder();
        UUID wrongCustomerId = UUID.fromString("00000000-0000-0000-0000-000000000002");

        // Payload validation against the addressed resource, not a state conflict: 400.
        assertCorrelationIdEchoed(givenWithGatewayAuth()
                .contentType(ContentType.JSON)
                .body(approvalPayload(wrongCustomerId))
                .when()
                .post("/v1/workorders/{workorderId}/approval", workorderId)
                .then()
                .statusCode(400)
                .body("code", equalTo("INVALID_ARGUMENT"))
                .log()
                .ifValidationFails()
                .extract());
    }

    // ========== SEED / HELPER METHODS ==========

    private String approvalPayload(UUID customerId) {
        return String.format("""
                {
                  "customerId": "%s",
                  "signatureData": "test-signature",
                  "signatureMimeType": "image/png",
                  "signerName": "Test Customer",
                  "notes": "Approval contract test"
                }
                """, customerId);
    }

    /**
     * Seed a DRAFT workorder by promoting an APPROVED estimate, which is the only
     * path that produces one.
     */
    private UUID seedDraftWorkorder() {
        initTestIds();

        Estimate estimate = Estimate.builder()
                .estimateNumber("EST-APPR-" + UUID.randomUUID().toString().substring(0, 8))
                .customerId(testCustomerId)
                .vehicleId(testVehicleId)
                .locationId(testLocationId)
                .status(EstimateStatus.APPROVED)
                .approvedAt(LocalDateTime.now(TEST_CLOCK).minusHours(1))
                .approvedBy(testCustomerId)
                .expiresAt(LocalDateTime.now(TEST_CLOCK).plusDays(30))
                .subtotal(new BigDecimal("100.00"))
                .taxAmount(new BigDecimal("8.00"))
                .total(new BigDecimal("108.00"))
                .currencyUomId("USD")
                .createdByUserId("test-user")
                .createdById("test-user")
                .build();
        estimate = estimateRepository.save(estimate);

        EstimateItem item = EstimateItem.builder()
                .estimate(estimate)
                .itemType(EstimateItemType.LABOR)
                .description("Oil change labor")
                .quantity(new BigDecimal("1.0000"))
                .unitPrice(new BigDecimal("100.00"))
                .lineTotal(new BigDecimal("100.00"))
                .taxCode("LABOR_TAX")
                .approvalStatus(ApprovalStatus.APPROVED)
                .approvalTimestamp(LocalDateTime.now(TEST_CLOCK).minusHours(1))
                .createdById("test-user")
                .build();
        estimateItemRepository.save(item);

        String workorderIdStr = givenWithGatewayAuth()
                .when()
                .post("/v1/workorders/estimates/{id}/promote", estimate.getId())
                .then()
                .statusCode(200)
                .extract()
                .path("id");

        return UUID.fromString(workorderIdStr);
    }

    private void initTestIds() {
        if (testCustomerId == null) {
            testCustomerId = UUID.fromString("00000000-0000-0000-0000-000000000001");
            testLocationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
            testVehicleId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        }
    }
}
