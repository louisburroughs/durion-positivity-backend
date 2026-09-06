package com.positivity.workorder.contract;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

import com.positivity.workorder.internal.entity.Estimate;
import com.positivity.workorder.internal.entity.EstimateItem;
import com.positivity.workorder.internal.entity.EstimateItemType;
import com.positivity.workorder.internal.entity.ExtBillingRulesReplica;
import com.positivity.workorder.internal.enums.EstimateStatus;
import com.positivity.workorder.internal.repository.EstimateItemRepository;
import com.positivity.workorder.internal.repository.EstimateRepository;
import com.positivity.workorder.internal.repository.ExtBillingRulesReplicaRepository;
import com.positivity.workorder.support.BaseContractIntegrationTest;
import io.restassured.http.ContentType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Contract behavior integration tests for estimate approval workflows.
 * CAP:003 - Capture Customer Approval
 *
 * Tests cover:
 * - Submit for approval (Issue #168)
 * - Digital signature approval with selective line item approval (Issue #207,
 * #205)
 * - In-person approval (Issue #206)
 * - Approval expiration (Issue #204)
 * - Customer ID mismatch rejection
 * - Invalid state transitions
 */
@DisplayName("Estimate Approval Contract Behavior Tests (CAP:003)")
class EstimateApprovalContractBehaviorIT extends BaseContractIntegrationTest {

    @Autowired
    private EstimateRepository estimateRepository;

    @Autowired
    private EstimateItemRepository estimateItemRepository;

    @Autowired
    private ExtBillingRulesReplicaRepository extBillingRulesReplicaRepository;

    private UUID testCustomerId;
    private UUID testLocationId;
    private UUID testVehicleId;

    @AfterEach
    void tearDown() {
        purgeTestData();
    }

    // ========== SUBMIT FOR APPROVAL TESTS (Issue #168) ==========

    @Test
    @DisplayName("AP-001: Successfully submit complete draft estimate for approval")
    void testSubmitForApproval_HappyPath() {
        UUID estimateId = seedCompleteEstimate();

        givenWithGatewayAuth()
                .when()
                .post("/v1/workorders/estimates/{id}/submit-for-approval", estimateId)
                .then()
                .statusCode(200)
                .body("id", equalTo(estimateId.toString()))
                .body("status", equalTo("PENDING_APPROVAL"))
                .body("submittedAt", notNullValue())
                .log()
                .ifValidationFails();
    }

    // AP-002 through AP-006 pin the statuses #1791 decided for submitForApproval, which #1773
    // deliberately left at a bodiless 400 because its refusals were not one question. They are
    // now two: "not in DRAFT" is a lifecycle-transition guard on the target's own status field,
    // 409 by ADR-0017 §2 exactly as on the approval endpoints; the four completeness rules (no
    // customer, no vehicle, no line items, totals not calculated) refuse a DRAFT estimate for an
    // attribute other than its status, on a request that carries no body to correct, which is
    // ADR-0017 §2's 422 ("a claim with no lines"). Each asserts the ApiError code and the
    // correlation id echoed between body and X-Correlation-Id header, the way AP-013 to AP-016
    // do: an assertion on the status alone is what let the original #1753 defect survive.
    @Test
    @DisplayName("AP-002: Reject submit for approval - estimate has no line items")
    void testSubmitForApproval_NoLineItems() {
        UUID estimateId = seedEstimateWithoutItems();

        assertCorrelationIdEchoed(givenWithGatewayAuth()
                .when()
                .post("/v1/workorders/estimates/{id}/submit-for-approval", estimateId)
                .then()
                .statusCode(422)
                .body("code", equalTo("ESTIMATE_INCOMPLETE"))
                .body("message", containsString("no line items"))
                .log()
                .ifValidationFails()
                .extract());
    }

    @Test
    @DisplayName("AP-003: Reject submit for approval - totals not calculated")
    void testSubmitForApproval_TotalsNotCalculated() {
        UUID estimateId = seedEstimateWithItemsNoTotals();

        assertCorrelationIdEchoed(givenWithGatewayAuth()
                .when()
                .post("/v1/workorders/estimates/{id}/submit-for-approval", estimateId)
                .then()
                .statusCode(422)
                .body("code", equalTo("ESTIMATE_INCOMPLETE"))
                .body("message", containsString("totals not calculated"))
                .log()
                .ifValidationFails()
                .extract());
    }

    @Test
    @DisplayName("AP-004: Reject submit for approval - estimate not in DRAFT state")
    void testSubmitForApproval_InvalidState() {
        UUID estimateId = seedApprovedEstimate();

        assertCorrelationIdEchoed(givenWithGatewayAuth()
                .when()
                .post("/v1/workorders/estimates/{id}/submit-for-approval", estimateId)
                .then()
                .statusCode(409)
                .body("code", equalTo("CONFLICT"))
                .body("message", containsString("must be in DRAFT state"))
                .log()
                .ifValidationFails()
                .extract());
    }

    @Test
    @DisplayName("AP-005: Reject submit for approval - estimate has no customer")
    void testSubmitForApproval_NoCustomer() {
        UUID estimateId = seedCompleteEstimateMissing(estimate -> estimate.setCustomerId(null));

        assertCorrelationIdEchoed(givenWithGatewayAuth()
                .when()
                .post("/v1/workorders/estimates/{id}/submit-for-approval", estimateId)
                .then()
                .statusCode(422)
                .body("code", equalTo("ESTIMATE_INCOMPLETE"))
                .body("message", containsString("no customer"))
                .log()
                .ifValidationFails()
                .extract());
    }

    @Test
    @DisplayName("AP-006: Reject submit for approval - estimate has no vehicle")
    void testSubmitForApproval_NoVehicle() {
        UUID estimateId = seedCompleteEstimateMissing(estimate -> estimate.setVehicleId(null));

        assertCorrelationIdEchoed(givenWithGatewayAuth()
                .when()
                .post("/v1/workorders/estimates/{id}/submit-for-approval", estimateId)
                .then()
                .statusCode(422)
                .body("code", equalTo("ESTIMATE_INCOMPLETE"))
                .body("message", containsString("no vehicle"))
                .log()
                .ifValidationFails()
                .extract());
    }

    // ========== DIGITAL APPROVAL WITH SELECTIVE LINE ITEM TESTS (Issue #207, #205)
    // ==========

    @Test
    @DisplayName("AP-010: Successfully approve estimate with all line items approved")
    void testApproveEstimate_AllItemsApproved() {
        UUID estimateId = seedPendingApprovalEstimate();

        String approvalPayload =
                buildApprovalPayload(testCustomerId, "Test Signer", "Approved all services", null, null);

        givenWithGatewayAuth()
                .contentType(ContentType.JSON)
                .body(approvalPayload)
                .when()
                .post("/v1/workorders/estimates/{id}/approval", estimateId)
                .then()
                .statusCode(200)
                .body("status", equalTo("APPROVED"))
                .body("approvedAt", notNullValue())
                .body("signerName", equalTo("Test Signer"))
                .log()
                .ifValidationFails();
    }

    @Test
    @DisplayName("AP-011: Successfully approve estimate with selective line item approval")
    void testApproveEstimate_SelectiveLineItems() {
        UUID estimateId = seedPendingApprovalEstimateWithMultipleItems();

        // Retrieve actual item IDs from the database
        List<EstimateItem> items = estimateItemRepository.findByEstimate_IdAndDeletedFalse(estimateId);
        UUID approvedItemId = items.get(0).getId();
        UUID declinedItemId = items.get(1).getId();

        String approvalPayload = buildApprovalPayloadWithLineItems(
                testCustomerId,
                "Test Signer",
                "Partial approval",
                approvedItemId,
                true,
                null,
                declinedItemId,
                false,
                "Customer declined optional service");

        givenWithGatewayAuth()
                .contentType(ContentType.JSON)
                .body(approvalPayload)
                .when()
                .post("/v1/workorders/estimates/{id}/approval", estimateId)
                .then()
                .statusCode(200)
                .body("status", equalTo("APPROVED"))
                .log()
                .ifValidationFails();
    }

    @Test
    @DisplayName("AP-012: Successfully approve estimate with purchase order (commercial account)")
    void testApproveEstimate_WithPurchaseOrder() {
        UUID estimateId = seedPendingApprovalEstimate();

        String approvalPayload =
                buildApprovalPayload(testCustomerId, "Test Signer", "Approved with PO", "PO-2024-12345", null);

        givenWithGatewayAuth()
                .contentType(ContentType.JSON)
                .body(approvalPayload)
                .when()
                .post("/v1/workorders/estimates/{id}/approval", estimateId)
                .then()
                .statusCode(200)
                .body("status", equalTo("APPROVED"))
                .body("purchaseOrderNumber", equalTo("PO-2024-12345"))
                .log()
                .ifValidationFails();
    }

    @Test
    @DisplayName("AP-013: Reject approval - customer ID mismatch")
    void testApproveEstimate_CustomerMismatch() {
        UUID estimateId = seedPendingApprovalEstimate();

        UUID wrongCustomerId = UUID.fromString("00000000-0000-0000-0000-000000000002");
        String approvalPayload = buildApprovalPayload(wrongCustomerId, "Test Signer", "Wrong customer", null, null);

        assertCorrelationIdEchoed(givenWithGatewayAuth()
                .contentType(ContentType.JSON)
                .body(approvalPayload)
                .when()
                .post("/v1/workorders/estimates/{id}/approval", estimateId)
                .then()
                .statusCode(400)
                .body("code", equalTo("INVALID_ARGUMENT"))
                .log()
                .ifValidationFails()
                .extract());
    }

    @Test
    @DisplayName("AP-014: Reject approval - estimate not in PENDING_APPROVAL state")
    void testApproveEstimate_InvalidState() {
        // Seed a DRAFT estimate (not submitted for approval)
        UUID estimateId = seedCompleteEstimate();

        String approvalPayload =
                buildApprovalPayload(testCustomerId, "Test Signer", "Trying to approve draft", null, null);

        assertCorrelationIdEchoed(givenWithGatewayAuth()
                .contentType(ContentType.JSON)
                .body(approvalPayload)
                .when()
                .post("/v1/workorders/estimates/{id}/approval", estimateId)
                .then()
                .statusCode(409)
                .body("code", equalTo("CONFLICT"))
                .log()
                .ifValidationFails()
                .extract());
    }

    // AP-015 covers the other half of #1753: approveEstimate had a second local catch, on
    // jakarta's EntityNotFoundException, that answered a bodiless 404. The published contract for
    // that response has since been corrected twice — springdoc originally inferred EstimateResponse
    // from the success type because the @ApiResponse carried no content, #1751 replaced that with an
    // explicitly empty @Content rather than claim a body that did not exist, and #1773 declared the
    // ApiError once the body was real. The service now throws EstimateNotFoundException and the
    // module advice envelopes it, but no contract test asserted the resulting envelope. Asserting
    // the status alone is what let the empty body survive on the sibling branch, so this pins the
    // code and the correlation id the same way AP-013/AP-014 do.
    @Test
    @DisplayName("AP-015: Reject approval - estimate does not exist")
    void testApproveEstimate_NotFound() {
        initTestIds();
        UUID unknownEstimateId = UUID.randomUUID();

        String approvalPayload =
                buildApprovalPayload(testCustomerId, "Test Signer", "Approving a missing estimate", null, null);

        assertCorrelationIdEchoed(givenWithGatewayAuth()
                .contentType(ContentType.JSON)
                .body(approvalPayload)
                .when()
                .post("/v1/workorders/estimates/{id}/approval", unknownEstimateId)
                .then()
                .statusCode(404)
                .body("code", equalTo("ESTIMATE_NOT_FOUND"))
                .log()
                .ifValidationFails()
                .extract());
    }

    // AP-016 completes the endpoint's error surface. approveEstimate documents four error
    // responses (400, 404, 409, 422) and, before this, the 422 was pinned only at unit level —
    // no contract test named PURCHASE_ORDER_REQUIRED at all. It needs no mock: PO enforcement
    // reads the event-fed ext_billing_rules replica (ADR-0044 §6), so seeding the row for this
    // customer is enough, and purgeTestData truncates it before every test.
    @Test
    @DisplayName("AP-016: Reject approval - commercial account requires a purchase order")
    void testApproveEstimate_PurchaseOrderRequired() {
        UUID estimateId = seedPendingApprovalEstimate();
        extBillingRulesReplicaRepository.save(ExtBillingRulesReplica.builder()
                .partyId(testCustomerId.toString())
                .purchaseOrderRequired(true)
                // updated_at is NOT NULL on the replica: pos-invoice stamps every fact it emits,
                // so a row without one could not arrive through the consumer either.
                .updatedAt(Instant.parse("2024-01-01T00:00:00Z"))
                .build());

        String approvalPayload =
                buildApprovalPayload(testCustomerId, "Test Signer", "Approving without a PO", null, null);

        assertCorrelationIdEchoed(givenWithGatewayAuth()
                .contentType(ContentType.JSON)
                .body(approvalPayload)
                .when()
                .post("/v1/workorders/estimates/{id}/approval", estimateId)
                .then()
                .statusCode(422)
                .body("code", equalTo("PURCHASE_ORDER_REQUIRED"))
                .log()
                .ifValidationFails()
                .extract());
    }

    // ========== SEED / HELPER METHODS ==========

    /**
     * Seed a complete DRAFT estimate with line items and calculated totals.
     * Ready to be submitted for approval.
     */
    private UUID seedCompleteEstimate() {
        initTestIds();

        Estimate estimate = Estimate.builder()
                .estimateNumber("EST-AP-" + System.nanoTime())
                .locationId(testLocationId)
                .vehicleId(testVehicleId)
                .customerId(testCustomerId)
                .currencyUomId("USD")
                .status(EstimateStatus.DRAFT)
                .createdByUserId(SYSTEM_USER_ID.toString())
                .createdById(SYSTEM_USER_ID.toString())
                .subtotal(new BigDecimal("65.00"))
                .taxAmount(new BigDecimal("5.36"))
                .total(new BigDecimal("70.36"))
                .build();

        Estimate saved = estimateRepository.save(estimate);

        estimateItemRepository.save(buildItem(
                saved.getId(), EstimateItemType.LABOR, "Oil Change", new BigDecimal("1"), new BigDecimal("50.00")));
        estimateItemRepository.save(buildItem(
                saved.getId(), EstimateItemType.PART, "Oil Filter", new BigDecimal("1"), new BigDecimal("15.00")));

        return saved.getId();
    }

    /**
     * Seed a DRAFT estimate that would be complete but for the one field {@code clear} removes.
     * Both customer_id and vehicle_id are nullable on the estimate table, so the row persists and
     * the completeness check, not the database, is what refuses the submission.
     */
    private UUID seedCompleteEstimateMissing(Consumer<Estimate> clear) {
        initTestIds();

        Estimate estimate = Estimate.builder()
                .estimateNumber("EST-AP-MISSING-" + System.nanoTime())
                .locationId(testLocationId)
                .vehicleId(testVehicleId)
                .customerId(testCustomerId)
                .currencyUomId("USD")
                .status(EstimateStatus.DRAFT)
                .createdByUserId(SYSTEM_USER_ID.toString())
                .createdById(SYSTEM_USER_ID.toString())
                .subtotal(new BigDecimal("65.00"))
                .taxAmount(new BigDecimal("5.36"))
                .total(new BigDecimal("70.36"))
                .build();
        clear.accept(estimate);

        Estimate saved = estimateRepository.save(estimate);

        estimateItemRepository.save(buildItem(
                saved.getId(), EstimateItemType.LABOR, "Oil Change", new BigDecimal("1"), new BigDecimal("50.00")));

        return saved.getId();
    }

    /**
     * Seed a DRAFT estimate with no line items.
     */
    private UUID seedEstimateWithoutItems() {
        initTestIds();

        Estimate estimate = Estimate.builder()
                .estimateNumber("EST-AP-EMPTY-" + System.nanoTime())
                .locationId(testLocationId)
                .vehicleId(testVehicleId)
                .customerId(testCustomerId)
                .currencyUomId("USD")
                .status(EstimateStatus.DRAFT)
                .createdByUserId(SYSTEM_USER_ID.toString())
                .createdById(SYSTEM_USER_ID.toString())
                .build();

        return estimateRepository.save(estimate).getId();
    }

    /**
     * Seed a DRAFT estimate with items but no calculated totals (subtotal is null).
     */
    private UUID seedEstimateWithItemsNoTotals() {
        initTestIds();

        Estimate estimate = Estimate.builder()
                .estimateNumber("EST-AP-NOTOTALS-" + System.nanoTime())
                .locationId(testLocationId)
                .vehicleId(testVehicleId)
                .customerId(testCustomerId)
                .currencyUomId("USD")
                .status(EstimateStatus.DRAFT)
                .createdByUserId(SYSTEM_USER_ID.toString())
                .createdById(SYSTEM_USER_ID.toString())
                .build();

        Estimate saved = estimateRepository.save(estimate);

        estimateItemRepository.save(buildItem(
                saved.getId(), EstimateItemType.LABOR, "Service", new BigDecimal("1"), new BigDecimal("100.00")));

        return saved.getId();
    }

    /**
     * Seed an APPROVED estimate (past PENDING_APPROVAL, already approved).
     * Cannot be submitted for approval again.
     */
    private UUID seedApprovedEstimate() {
        initTestIds();

        Estimate estimate = Estimate.builder()
                .estimateNumber("EST-AP-APPROVED-" + System.nanoTime())
                .locationId(testLocationId)
                .vehicleId(testVehicleId)
                .customerId(testCustomerId)
                .currencyUomId("USD")
                .status(EstimateStatus.APPROVED)
                .createdByUserId(SYSTEM_USER_ID.toString())
                .createdById(SYSTEM_USER_ID.toString())
                .subtotal(new BigDecimal("100.00"))
                .taxAmount(new BigDecimal("8.25"))
                .total(new BigDecimal("108.25"))
                .build();

        Estimate saved = estimateRepository.save(estimate);

        estimateItemRepository.save(buildItem(
                saved.getId(), EstimateItemType.LABOR, "Brake Service", new BigDecimal("1"), new BigDecimal("100.00")));

        return saved.getId();
    }

    /**
     * Seed an estimate in PENDING_APPROVAL state with two line items.
     * Ready to be approved.
     */
    private UUID seedPendingApprovalEstimate() {
        initTestIds();

        Estimate estimate = Estimate.builder()
                .estimateNumber("EST-AP-PENDING-" + System.nanoTime())
                .locationId(testLocationId)
                .vehicleId(testVehicleId)
                .customerId(testCustomerId)
                .currencyUomId("USD")
                .status(EstimateStatus.PENDING_APPROVAL)
                .createdByUserId(SYSTEM_USER_ID.toString())
                .createdById(SYSTEM_USER_ID.toString())
                .subtotal(new BigDecimal("65.00"))
                .taxAmount(new BigDecimal("5.36"))
                .total(new BigDecimal("70.36"))
                .build();

        Estimate saved = estimateRepository.save(estimate);

        estimateItemRepository.save(buildItem(
                saved.getId(), EstimateItemType.LABOR, "Oil Change", new BigDecimal("1"), new BigDecimal("50.00")));
        estimateItemRepository.save(buildItem(
                saved.getId(), EstimateItemType.PART, "Oil Filter", new BigDecimal("1"), new BigDecimal("15.00")));

        return saved.getId();
    }

    /**
     * Seed an estimate in PENDING_APPROVAL state with multiple line items
     * for selective approval testing.
     */
    private UUID seedPendingApprovalEstimateWithMultipleItems() {
        initTestIds();

        Estimate estimate = Estimate.builder()
                .estimateNumber("EST-AP-MULTI-" + System.nanoTime())
                .locationId(testLocationId)
                .vehicleId(testVehicleId)
                .customerId(testCustomerId)
                .currencyUomId("USD")
                .status(EstimateStatus.PENDING_APPROVAL)
                .createdByUserId(SYSTEM_USER_ID.toString())
                .createdById(SYSTEM_USER_ID.toString())
                .subtotal(new BigDecimal("265.00"))
                .taxAmount(new BigDecimal("21.86"))
                .total(new BigDecimal("286.86"))
                .build();

        Estimate saved = estimateRepository.save(estimate);

        estimateItemRepository.save(buildItem(
                saved.getId(), EstimateItemType.LABOR, "Brake Service", new BigDecimal("1"), new BigDecimal("150.00")));
        estimateItemRepository.save(buildItem(
                saved.getId(), EstimateItemType.PART, "Brake Pads", new BigDecimal("1"), new BigDecimal("75.00")));
        estimateItemRepository.save(buildItem(
                saved.getId(), EstimateItemType.LABOR, "Tire Rotation", new BigDecimal("1"), new BigDecimal("40.00")));

        return saved.getId();
    }

    private void initTestIds() {
        testCustomerId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        testLocationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        testVehicleId = UUID.fromString("00000000-0000-0000-0000-000000000001");
    }

    private EstimateItem buildItem(
            UUID estimateId, EstimateItemType type, String description, BigDecimal quantity, BigDecimal unitPrice) {
        return EstimateItem.builder()
                .estimate(estimateRepository.getReferenceById(estimateId))
                .itemType(type)
                .description(description)
                .quantity(quantity)
                .unitPrice(unitPrice)
                .taxCode("STD")
                .createdById(SYSTEM_USER_ID.toString())
                .build();
    }

    /**
     * Build approval JSON payload (no selective line item approvals).
     */
    private String buildApprovalPayload(
            UUID customerId,
            String signerName,
            String notes,
            String purchaseOrderNumber,
            String lineItemApprovalsJson) {
        StringBuilder json = new StringBuilder("{");
        json.append("\"customerId\":\"").append(customerId).append("\",");
        json.append(
                "\"signatureData\":\"iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==\",");
        json.append("\"signatureMimeType\":\"image/png\",");
        json.append("\"signerName\":\"").append(signerName).append("\",");
        json.append("\"notes\":\"").append(notes).append("\"");

        if (purchaseOrderNumber != null) {
            json.append(",\"purchaseOrderNumber\":\"")
                    .append(purchaseOrderNumber)
                    .append("\"");
        }

        if (lineItemApprovalsJson != null) {
            json.append(",\"lineItemApprovals\":").append(lineItemApprovalsJson);
        }

        json.append("}");
        return json.toString();
    }

    /**
     * Build approval JSON payload with selective line item approvals.
     * Accepts triplets of (itemId, approved, rejectionReason).
     */
    private String buildApprovalPayloadWithLineItems(
            UUID customerId, String signerName, String notes, Object... lineItemData) {
        StringBuilder lineItems = new StringBuilder("[");
        for (int i = 0; i < lineItemData.length; i += 3) {
            if (i > 0) {
                lineItems.append(",");
            }
            UUID itemId = (UUID) lineItemData[i];
            boolean approved = (boolean) lineItemData[i + 1];
            String rejectionReason = (String) lineItemData[i + 2];

            lineItems.append("{\"lineItemId\":\"").append(itemId).append("\",");
            lineItems.append("\"approved\":").append(approved);
            if (rejectionReason != null) {
                lineItems
                        .append(",\"rejectionReason\":\"")
                        .append(rejectionReason)
                        .append("\"");
            }
            lineItems.append("}");
        }
        lineItems.append("]");

        return buildApprovalPayload(customerId, signerName, notes, null, lineItems.toString());
    }
}
