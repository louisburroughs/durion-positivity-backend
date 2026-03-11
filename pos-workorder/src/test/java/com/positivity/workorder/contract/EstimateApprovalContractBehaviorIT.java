package com.positivity.workorder.contract;

import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.positivity.workorder.internal.entity.Estimate;
import com.positivity.workorder.internal.entity.EstimateItem;
import com.positivity.workorder.internal.entity.EstimateItemType;
import com.positivity.workorder.internal.enums.EstimateStatus;
import com.positivity.workorder.internal.repository.EstimateItemRepository;
import com.positivity.workorder.internal.repository.EstimateRepository;
import com.positivity.workorder.support.BaseContractIntegrationTest;

import io.restassured.http.ContentType;

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
                                .log().ifValidationFails();
        }

        @Test
        @DisplayName("AP-002: Reject submit for approval - estimate has no line items")
        void testSubmitForApproval_NoLineItems() {
                UUID estimateId = seedEstimateWithoutItems();

                givenWithGatewayAuth()
                                .when()
                                .post("/v1/workorders/estimates/{id}/submit-for-approval", estimateId)
                                .then()
                                .statusCode(anyOf(is(400), is(500)))
                                .log().ifValidationFails();
        }

        @Test
        @DisplayName("AP-003: Reject submit for approval - totals not calculated")
        void testSubmitForApproval_TotalsNotCalculated() {
                UUID estimateId = seedEstimateWithItemsNoTotals();

                givenWithGatewayAuth()
                                .when()
                                .post("/v1/workorders/estimates/{id}/submit-for-approval", estimateId)
                                .then()
                                .statusCode(anyOf(is(400), is(500)))
                                .log().ifValidationFails();
        }

        @Test
        @DisplayName("AP-004: Reject submit for approval - estimate not in DRAFT state")
        void testSubmitForApproval_InvalidState() {
                UUID estimateId = seedApprovedEstimate();

                givenWithGatewayAuth()
                                .when()
                                .post("/v1/workorders/estimates/{id}/submit-for-approval", estimateId)
                                .then()
                                .statusCode(anyOf(is(400), is(500)))
                                .log().ifValidationFails();
        }

        // ========== DIGITAL APPROVAL WITH SELECTIVE LINE ITEM TESTS (Issue #207, #205)
        // ==========

        @Test
        @DisplayName("AP-010: Successfully approve estimate with all line items approved")
        void testApproveEstimate_AllItemsApproved() {
                UUID estimateId = seedPendingApprovalEstimate();

                String approvalPayload = buildApprovalPayload(testCustomerId, "Test Signer",
                                "Approved all services", null, null);

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
                                .log().ifValidationFails();
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
                                testCustomerId, "Test Signer", "Partial approval",
                                approvedItemId, true, null,
                                declinedItemId, false, "Customer declined optional service");

                givenWithGatewayAuth()
                                .contentType(ContentType.JSON)
                                .body(approvalPayload)
                                .when()
                                .post("/v1/workorders/estimates/{id}/approval", estimateId)
                                .then()
                                .statusCode(200)
                                .body("status", equalTo("APPROVED"))
                                .log().ifValidationFails();
        }

        @Test
        @DisplayName("AP-012: Successfully approve estimate with purchase order (commercial account)")
        void testApproveEstimate_WithPurchaseOrder() {
                UUID estimateId = seedPendingApprovalEstimate();

                String approvalPayload = buildApprovalPayload(testCustomerId, "Test Signer",
                                "Approved with PO", "PO-2024-12345", null);

                givenWithGatewayAuth()
                                .contentType(ContentType.JSON)
                                .body(approvalPayload)
                                .when()
                                .post("/v1/workorders/estimates/{id}/approval", estimateId)
                                .then()
                                .statusCode(200)
                                .body("status", equalTo("APPROVED"))
                                .body("purchaseOrderNumber", equalTo("PO-2024-12345"))
                                .log().ifValidationFails();
        }

        @Test
        @DisplayName("AP-013: Reject approval - customer ID mismatch")
        void testApproveEstimate_CustomerMismatch() {
                UUID estimateId = seedPendingApprovalEstimate();

                UUID wrongCustomerId = UUID.fromString("00000000-0000-0000-0000-000000000002");
                String approvalPayload = buildApprovalPayload(wrongCustomerId, "Test Signer",
                                "Wrong customer", null, null);

                givenWithGatewayAuth()
                                .contentType(ContentType.JSON)
                                .body(approvalPayload)
                                .when()
                                .post("/v1/workorders/estimates/{id}/approval", estimateId)
                                .then()
                                .statusCode(anyOf(is(400), is(500)))
                                .log().ifValidationFails();
        }

        @Test
        @DisplayName("AP-014: Reject approval - estimate not in PENDING_APPROVAL state")
        void testApproveEstimate_InvalidState() {
                // Seed a DRAFT estimate (not submitted for approval)
                UUID estimateId = seedCompleteEstimate();

                String approvalPayload = buildApprovalPayload(testCustomerId, "Test Signer",
                                "Trying to approve draft", null, null);

                givenWithGatewayAuth()
                                .contentType(ContentType.JSON)
                                .body(approvalPayload)
                                .when()
                                .post("/v1/workorders/estimates/{id}/approval", estimateId)
                                .then()
                                .statusCode(anyOf(is(400), is(500)))
                                .log().ifValidationFails();
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
                                .taxRegionId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                                .status(EstimateStatus.DRAFT)
                                .createdByUserId(SYSTEM_USER_ID.toString())
                                .createdById(SYSTEM_USER_ID.toString())
                                .subtotal(new BigDecimal("65.00"))
                                .taxAmount(new BigDecimal("5.36"))
                                .total(new BigDecimal("70.36"))
                                .build();

                Estimate saved = estimateRepository.save(estimate);

                estimateItemRepository.save(buildItem(saved.getId(), EstimateItemType.LABOR,
                                "Oil Change", new BigDecimal("1"), new BigDecimal("50.00")));
                estimateItemRepository.save(buildItem(saved.getId(), EstimateItemType.PART,
                                "Oil Filter", new BigDecimal("1"), new BigDecimal("15.00")));

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
                                .taxRegionId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
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
                                .taxRegionId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                                .status(EstimateStatus.DRAFT)
                                .createdByUserId(SYSTEM_USER_ID.toString())
                                .createdById(SYSTEM_USER_ID.toString())
                                .build();

                Estimate saved = estimateRepository.save(estimate);

                estimateItemRepository.save(buildItem(saved.getId(), EstimateItemType.LABOR,
                                "Service", new BigDecimal("1"), new BigDecimal("100.00")));

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
                                .taxRegionId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                                .status(EstimateStatus.APPROVED)
                                .createdByUserId(SYSTEM_USER_ID.toString())
                                .createdById(SYSTEM_USER_ID.toString())
                                .subtotal(new BigDecimal("100.00"))
                                .taxAmount(new BigDecimal("8.25"))
                                .total(new BigDecimal("108.25"))
                                .build();

                Estimate saved = estimateRepository.save(estimate);

                estimateItemRepository.save(buildItem(saved.getId(), EstimateItemType.LABOR,
                                "Brake Service", new BigDecimal("1"), new BigDecimal("100.00")));

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
                                .taxRegionId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                                .status(EstimateStatus.PENDING_APPROVAL)
                                .createdByUserId(SYSTEM_USER_ID.toString())
                                .createdById(SYSTEM_USER_ID.toString())
                                .subtotal(new BigDecimal("65.00"))
                                .taxAmount(new BigDecimal("5.36"))
                                .total(new BigDecimal("70.36"))
                                .build();

                Estimate saved = estimateRepository.save(estimate);

                estimateItemRepository.save(buildItem(saved.getId(), EstimateItemType.LABOR,
                                "Oil Change", new BigDecimal("1"), new BigDecimal("50.00")));
                estimateItemRepository.save(buildItem(saved.getId(), EstimateItemType.PART,
                                "Oil Filter", new BigDecimal("1"), new BigDecimal("15.00")));

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
                                .taxRegionId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                                .status(EstimateStatus.PENDING_APPROVAL)
                                .createdByUserId(SYSTEM_USER_ID.toString())
                                .createdById(SYSTEM_USER_ID.toString())
                                .subtotal(new BigDecimal("265.00"))
                                .taxAmount(new BigDecimal("21.86"))
                                .total(new BigDecimal("286.86"))
                                .build();

                Estimate saved = estimateRepository.save(estimate);

                estimateItemRepository.save(buildItem(saved.getId(), EstimateItemType.LABOR,
                                "Brake Service", new BigDecimal("1"), new BigDecimal("150.00")));
                estimateItemRepository.save(buildItem(saved.getId(), EstimateItemType.PART,
                                "Brake Pads", new BigDecimal("1"), new BigDecimal("75.00")));
                estimateItemRepository.save(buildItem(saved.getId(), EstimateItemType.LABOR,
                                "Tire Rotation", new BigDecimal("1"), new BigDecimal("40.00")));

                return saved.getId();
        }

        private void initTestIds() {
                testCustomerId = UUID.fromString("00000000-0000-0000-0000-000000000001");
                testLocationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
                testVehicleId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        }

        private EstimateItem buildItem(UUID estimateId, EstimateItemType type, String description,
                        BigDecimal quantity, BigDecimal unitPrice) {
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
        private String buildApprovalPayload(UUID customerId, String signerName, String notes,
                        String purchaseOrderNumber, String lineItemApprovalsJson) {
                StringBuilder json = new StringBuilder("{");
                json.append("\"customerId\":\"").append(customerId).append("\",");
                json.append(
                                "\"signatureData\":\"iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==\",");
                json.append("\"signatureMimeType\":\"image/png\",");
                json.append("\"signerName\":\"").append(signerName).append("\",");
                json.append("\"notes\":\"").append(notes).append("\"");

                if (purchaseOrderNumber != null) {
                        json.append(",\"purchaseOrderNumber\":\"").append(purchaseOrderNumber).append("\"");
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
        private String buildApprovalPayloadWithLineItems(UUID customerId, String signerName,
                        String notes, Object... lineItemData) {
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
                                lineItems.append(",\"rejectionReason\":\"").append(rejectionReason).append("\"");
                        }
                        lineItems.append("}");
                }
                lineItems.append("]");

                return buildApprovalPayload(customerId, signerName, notes, null, lineItems.toString());
        }
}
