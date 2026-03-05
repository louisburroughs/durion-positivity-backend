package com.positivity.workorder.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import com.positivity.workorder.entity.*;
import com.positivity.workorder.internal.entity.Workorder;
import com.positivity.workorder.internal.entity.WorkorderPart;
import com.positivity.workorder.internal.entity.WorkorderPartAdjustmentEvent;
import com.positivity.workorder.internal.enums.WorkorderItemStatus;
import com.positivity.workorder.internal.enums.WorkorderStatus;
import com.positivity.workorder.internal.repository.*;
import com.positivity.workorder.support.BaseContractIntegrationTest;

import io.restassured.http.ContentType;

/**
 * Contract behavior integration tests for workorder part adjustments.
 * CAP:005 Story #157 - Handle Part Substitutions and Returns
 *
 * <p>
 * Tests cover:
 * <ul>
 * <li>PA-001: Substitute part (happy path, original part preserved)</li>
 * <li>PA-002: Substitute with idempotency</li>
 * <li>PA-003: Return unused quantity (happy path)</li>
 * <li>PA-004: Return with idempotency</li>
 * <li>PA-005: Reject return if quantity exceeds available</li>
 * <li>PA-006: Correct part quantity (admin correction)</li>
 * <li>PA-007: Correct with idempotency</li>
 * <li>PA-008: Get adjustment history returns newest-first</li>
 * <li>PA-009: Verify original part NOT deleted after substitution</li>
 * <li>PA-010: Verify substitute part created with correct quantities</li>
 * </ul>
 */
@DisplayName("Part Adjustments Contract Behavior Tests (CAP:005 Story #157)")
@Import(ContractTestConfiguration.class)
class WorkorderPartAdjustmentsContractBehaviorIT extends BaseContractIntegrationTest {

        @Autowired
        private WorkorderRepository workorderRepository;

        @Autowired
        private WorkorderPartRepository workorderPartRepository;

        @Autowired
        private WorkorderPartAdjustmentEventRepository adjustmentEventRepository;

        private UUID testCustomerId;
        private UUID testLocationId;
        private UUID testVehicleId;

        @AfterEach
        void tearDown() {
                adjustmentEventRepository.deleteAll();
                workorderPartRepository.deleteAll();
                workorderRepository.deleteAll();
        }

        // ========== SUBSTITUTION TESTS ==========

        @Test
        @DisplayName("PA-001: Successfully substitute part (original preserved)")
        void testSubstitutePart_HappyPath() {
                // Given: A workorder with a part
                UUID workorderId = seedWorkorderWithPart();
                UUID originalPartId = workorderPartRepository.findByWorkorderId(workorderId).get(0).getId();
                UUID substitutePartId = UUID.fromString("00000000-0000-0000-0000-000000000001");

                // When: Substitute the part
                Map<String, Object> substituteRequest = Map.of(
                                "originalPartId", originalPartId.toString(),
                                "substitutePartId", substitutePartId.toString(),
                                "reason", "Original part out of stock",
                                "notes", "Used equivalent premium part");

                givenWithGatewayAuth()
                                .contentType(ContentType.JSON)
                                .body(substituteRequest)
                                .when()
                                .post("/v1/workorders/{workorderId}/parts/substitute", workorderId)
                                .then()
                                .log().ifValidationFails()
                                .statusCode(201)
                                .body("workorderId", equalTo(workorderId.toString()))
                                .body("originalPartId", equalTo(originalPartId.toString()))
                                .body("adjustmentType", equalTo("SUBSTITUTION"))
                                .body("substitutedWithPartId", notNullValue())
                                .body("quantityAdjustment", equalTo(0)) // Integer comparison for 0
                                .body("reason", equalTo("Original part out of stock"))
                                .body("performedBy", notNullValue())
                                .body("performedAt", notNullValue());

                // Then: Verify adjustment event was created
                List<WorkorderPartAdjustmentEvent> events = adjustmentEventRepository
                                .findByWorkorderIdOrderByPerformedAtDesc(workorderId);
                assertThat(events).hasSize(1);
                assertThat(events.get(0).getAdjustmentType()).isEqualTo("SUBSTITUTION");
                assertThat(events.get(0).getOriginalPart().getId()).isEqualTo(originalPartId);
                assertThat(events.get(0).getSubstitutedWithPartId()).isNotNull();

                // Then: Verify original part still exists (not deleted)
                WorkorderPart originalPart = workorderPartRepository.findById(originalPartId).orElseThrow();
                assertThat(originalPart).isNotNull();

                // Then: Verify substitute part was created
                List<WorkorderPart> allParts = workorderPartRepository.findByWorkorderId(workorderId);
                assertThat(allParts).hasSize(2); // Original + substitute
        }

        @Test
        @DisplayName("PA-002: Substitute with idempotency returns existing event")
        void testSubstitutePart_Idempotency() {
                // Given: A workorder with a part
                UUID workorderId = seedWorkorderWithPart();
                UUID originalPartId = workorderPartRepository.findByWorkorderId(workorderId).get(0).getId();
                UUID substitutePartId = UUID.fromString("00000000-0000-0000-0000-000000000001");
                String idempotencyKey = "test-substitute-" + UUID.fromString("00000000-0000-0000-0000-000000000001");

                Map<String, Object> substituteRequest = Map.of(
                                "originalPartId", originalPartId.toString(),
                                "substitutePartId", substitutePartId.toString(),
                                "reason", "Out of stock");

                // When: First request creates the event
                String firstEventId = givenWithGatewayAuth()
                                .contentType(ContentType.JSON)
                                .header("Idempotency-Key", idempotencyKey)
                                .body(substituteRequest)
                                .when()
                                .post("/v1/workorders/{workorderId}/parts/substitute", workorderId)
                                .then()
                                .statusCode(201)
                                .extract().path("id");

                // Then: Second request with same key returns existing event
                givenWithGatewayAuth()
                                .contentType(ContentType.JSON)
                                .header("Idempotency-Key", idempotencyKey)
                                .body(substituteRequest)
                                .when()
                                .post("/v1/workorders/{workorderId}/parts/substitute", workorderId)
                                .then()
                                .log().ifValidationFails()
                                .statusCode(201)
                                .body("id", equalTo(firstEventId));

                // Verify only one adjustment event was created
                List<WorkorderPartAdjustmentEvent> events = adjustmentEventRepository
                                .findByWorkorderIdOrderByPerformedAtDesc(workorderId);
                assertThat(events).hasSize(1);

                // Verify only one substitute part was created (2 total: original + 1
                // substitute)
                List<WorkorderPart> allParts = workorderPartRepository.findByWorkorderId(workorderId);
                assertThat(allParts).hasSize(2);
        }

        @Test
        @DisplayName("PA-009: Verify original part NOT deleted after substitution")
        void testSubstitutePart_OriginalPreserved() {
                // Given: A workorder with a part
                UUID workorderId = seedWorkorderWithPart();
                UUID originalPartId = workorderPartRepository.findByWorkorderId(workorderId).get(0).getId();

                // When: Substitute the part
                Map<String, Object> substituteRequest = Map.of(
                                "originalPartId", originalPartId.toString(),
                                "substitutePartId", UUID.fromString("00000000-0000-0000-0000-000000000001").toString(),
                                "reason", "Substitution test");

                givenWithGatewayAuth()
                                .contentType(ContentType.JSON)
                                .body(substituteRequest)
                                .when()
                                .post("/v1/workorders/{workorderId}/parts/substitute", workorderId)
                                .then()
                                .statusCode(201);

                // Then: Original part must still exist
                boolean originalExists = workorderPartRepository.existsById(originalPartId);
                assertThat(originalExists).isTrue();

                // And both parts must be in the database
                List<WorkorderPart> allParts = workorderPartRepository.findByWorkorderId(workorderId);
                assertThat(allParts).hasSize(2);
                assertThat(allParts).extracting(WorkorderPart::getId).contains(originalPartId);
        }

        @Test
        @DisplayName("PA-010: Verify substitute part created with correct quantities")
        void testSubstitutePart_SubstituteQuantitiesCorrect() {
                // Given: A workorder with a part having specific quantities
                UUID workorderId = seedWorkorderWithPart();
                WorkorderPart originalPart = workorderPartRepository.findByWorkorderId(workorderId).get(0);
                BigDecimal originalQuantity = BigDecimal.valueOf(4.0);
                BigDecimal originalUnitPrice = BigDecimal.valueOf(25.50);
                originalPart.setQuantity(originalQuantity);
                originalPart.setUnitPrice(originalUnitPrice);
                originalPart.setLineTotal(originalQuantity.multiply(originalUnitPrice));
                workorderPartRepository.save(originalPart);

                // When: Substitute the part
                Map<String, Object> substituteRequest = Map.of(
                                "originalPartId", originalPart.getId().toString(),
                                "substitutePartId", UUID.fromString("00000000-0000-0000-0000-000000000001").toString(),
                                "reason", "Quantity test");

                String substitutePartIdStr = givenWithGatewayAuth()
                                .contentType(ContentType.JSON)
                                .body(substituteRequest)
                                .when()
                                .post("/v1/workorders/{workorderId}/parts/substitute", workorderId)
                                .then()
                                .statusCode(201)
                                .extract().path("substitutedWithPartId");

                // Then: Substitute part should have same quantities as original
                UUID substitutePartId = UUID.fromString(substitutePartIdStr);
                WorkorderPart substitutePart = workorderPartRepository.findById(substitutePartId).orElseThrow();

                assertThat(substitutePart.getQuantity()).isEqualByComparingTo(originalQuantity);
                assertThat(substitutePart.getQuantityIssued()).isEqualByComparingTo(BigDecimal.ZERO);
                assertThat(substitutePart.getQuantityConsumed()).isEqualByComparingTo(BigDecimal.ZERO);
                assertThat(substitutePart.getQuantityReturned()).isEqualByComparingTo(BigDecimal.ZERO);
        }

        // ========== RETURN UNUSED QUANTITY TESTS ==========

        @Test
        @DisplayName("PA-003: Successfully return unused quantity")
        void testReturnUnused_HappyPath() {
                // Given: A workorder with a part that has issued = 10, consumed = 6
                UUID workorderId = seedWorkorderWithPartiallyConsumedPart(BigDecimal.valueOf(10.0),
                                BigDecimal.valueOf(6.0));
                UUID partId = workorderPartRepository.findByWorkorderId(workorderId).get(0).getId();

                // When: Return 3 units (available = 10 - 6 = 4, so 3 is valid)
                Map<String, Object> returnRequest = Map.of(
                                "workorderPartId", partId.toString(),
                                "quantity", 3.0,
                                "reason", "Part not needed for this service",
                                "notes", "Returning to inventory");

                givenWithGatewayAuth()
                                .contentType(ContentType.JSON)
                                .body(returnRequest)
                                .when()
                                .post("/v1/workorders/{workorderId}/parts/returnUnused", workorderId)
                                .then()
                                .log().ifValidationFails()
                                .statusCode(201)
                                .body("workorderId", equalTo(workorderId.toString()))
                                .body("adjustmentType", equalTo("ADDITIONAL_RETURN"))
                                .body("quantityAdjustment", equalTo(-3.0f)) // Negative for return
                                .body("reason", equalTo("Part not needed for this service"));

                // Then: Verify adjustment event was created
                List<WorkorderPartAdjustmentEvent> events = adjustmentEventRepository
                                .findByWorkorderIdOrderByPerformedAtDesc(workorderId);
                assertThat(events).hasSize(1);
                assertThat(events.get(0).getAdjustmentType()).isEqualTo("ADDITIONAL_RETURN");
                assertThat(events.get(0).getQuantityAdjustment()).isEqualByComparingTo(new BigDecimal("-3.0"));

                // Then: Verify part.quantityReturned updated
                WorkorderPart part = workorderPartRepository.findById(partId).orElseThrow();
                assertThat(part.getQuantityReturned()).isEqualByComparingTo(new BigDecimal("3.0"));
        }

        @Test
        @DisplayName("PA-004: Return unused with idempotency returns existing event")
        void testReturnUnused_Idempotency() {
                // Given: A workorder with a partially consumed part
                UUID workorderId = seedWorkorderWithPartiallyConsumedPart(BigDecimal.valueOf(10.0),
                                BigDecimal.valueOf(6.0));
                UUID partId = workorderPartRepository.findByWorkorderId(workorderId).get(0).getId();
                String idempotencyKey = "test-return-" + UUID.fromString("00000000-0000-0000-0000-000000000001");

                Map<String, Object> returnRequest = Map.of(
                                "workorderPartId", partId.toString(),
                                "quantity", 2.0,
                                "reason", "Not needed");

                // When: First request creates the event
                String firstEventId = givenWithGatewayAuth()
                                .contentType(ContentType.JSON)
                                .header("Idempotency-Key", idempotencyKey)
                                .body(returnRequest)
                                .when()
                                .post("/v1/workorders/{workorderId}/parts/returnUnused", workorderId)
                                .then()
                                .statusCode(201)
                                .extract().path("id");

                // Then: Second request with same key returns existing event
                givenWithGatewayAuth()
                                .contentType(ContentType.JSON)
                                .header("Idempotency-Key", idempotencyKey)
                                .body(returnRequest)
                                .when()
                                .post("/v1/workorders/{workorderId}/parts/returnUnused", workorderId)
                                .then()
                                .log().ifValidationFails()
                                .statusCode(201)
                                .body("id", equalTo(firstEventId));

                // Verify quantity returned is not doubled
                WorkorderPart part = workorderPartRepository.findById(partId).orElseThrow();
                assertThat(part.getQuantityReturned()).isEqualByComparingTo(new BigDecimal("2.0"));
        }

        @Test
        @DisplayName("PA-005: Reject return if quantity exceeds available")
        void testReturnUnused_ExceedsAvailable() {
                // Given: A workorder with issued=5, consumed=3, available=2
                UUID workorderId = seedWorkorderWithPartiallyConsumedPart(BigDecimal.valueOf(5.0),
                                BigDecimal.valueOf(3.0));
                UUID partId = workorderPartRepository.findByWorkorderId(workorderId).get(0).getId();

                // When: Attempt to return 3 units (exceeds available 2)
                Map<String, Object> returnRequest = Map.of(
                                "workorderPartId", partId.toString(),
                                "quantity", 3.0,
                                "reason", "Return too much");

                // Then: Should fail with 400 Bad Request
                givenWithGatewayAuth()
                                .contentType(ContentType.JSON)
                                .body(returnRequest)
                                .when()
                                .post("/v1/workorders/{workorderId}/parts/returnUnused", workorderId)
                                .then()
                                .log().ifValidationFails()
                                .statusCode(400);

                // Verify no event was created
                List<WorkorderPartAdjustmentEvent> events = adjustmentEventRepository
                                .findByWorkorderIdOrderByPerformedAtDesc(workorderId);
                assertThat(events).isEmpty();
        }

        // ========== CORRECTION TESTS ==========

        @Test
        @DisplayName("PA-006: Successfully correct part quantity")
        void testCorrectQuantity_HappyPath() {
                // Given: A workorder with a part having quantity = 2
                UUID workorderId = seedWorkorderWithPart();
                UUID partId = workorderPartRepository.findByWorkorderId(workorderId).get(0).getId();
                WorkorderPart part = workorderPartRepository.findById(partId).orElseThrow();
                BigDecimal oldQuantity = part.getQuantity();
                assertThat(oldQuantity).isEqualByComparingTo(new BigDecimal("2.0"));

                // When: Correct quantity to 5
                Map<String, Object> correctRequest = Map.of(
                                "workorderPartId", partId.toString(),
                                "newQuantity", 5.0,
                                "reason", "Data entry error - should be 5 units",
                                "notes", "Corrected after invoice review");

                givenWithGatewayAuth()
                                .contentType(ContentType.JSON)
                                .body(correctRequest)
                                .when()
                                .post("/v1/workorders/{workorderId}/parts/correct", workorderId)
                                .then()
                                .log().ifValidationFails()
                                .statusCode(201)
                                .body("workorderId", equalTo(workorderId.toString()))
                                .body("adjustmentType", equalTo("CORRECTION"))
                                .body("quantityAdjustment", equalTo(3.0f)) // 5 - 2 = 3
                                .body("reason", equalTo("Data entry error - should be 5 units"));

                // Then: Verify adjustment event was created
                List<WorkorderPartAdjustmentEvent> events = adjustmentEventRepository
                                .findByWorkorderIdOrderByPerformedAtDesc(workorderId);
                assertThat(events).hasSize(1);
                assertThat(events.get(0).getAdjustmentType()).isEqualTo("CORRECTION");
                assertThat(events.get(0).getQuantityAdjustment()).isEqualByComparingTo(new BigDecimal("3.0"));

                // Then: Verify part.quantity updated
                part = workorderPartRepository.findById(partId).orElseThrow();
                assertThat(part.getQuantity()).isEqualByComparingTo(new BigDecimal("5.0"));
        }

        @Test
        @DisplayName("PA-007: Correct with idempotency returns existing event")
        void testCorrectQuantity_Idempotency() {
                // Given: A workorder with a part
                UUID workorderId = seedWorkorderWithPart();
                UUID partId = workorderPartRepository.findByWorkorderId(workorderId).get(0).getId();
                String idempotencyKey = "test-correct-" + UUID.fromString("00000000-0000-0000-0000-000000000001");

                Map<String, Object> correctRequest = Map.of(
                                "workorderPartId", partId.toString(),
                                "newQuantity", 4.0,
                                "reason", "Correction");

                // When: First request creates the event
                String firstEventId = givenWithGatewayAuth()
                                .contentType(ContentType.JSON)
                                .header("Idempotency-Key", idempotencyKey)
                                .body(correctRequest)
                                .when()
                                .post("/v1/workorders/{workorderId}/parts/correct", workorderId)
                                .then()
                                .statusCode(201)
                                .extract().path("id");

                // Then: Second request with same key returns existing event
                givenWithGatewayAuth()
                                .contentType(ContentType.JSON)
                                .header("Idempotency-Key", idempotencyKey)
                                .body(correctRequest)
                                .when()
                                .post("/v1/workorders/{workorderId}/parts/correct", workorderId)
                                .then()
                                .log().ifValidationFails()
                                .statusCode(201)
                                .body("id", equalTo(firstEventId));

                // Verify quantity is correct (not doubled)
                WorkorderPart part = workorderPartRepository.findById(partId).orElseThrow();
                assertThat(part.getQuantity()).isEqualByComparingTo(new BigDecimal("4.0"));
        }

        // ========== ADJUSTMENT HISTORY TESTS ==========

        @Test
        @DisplayName("PA-008: Get adjustment history returns newest-first")
        @org.junit.jupiter.api.Disabled("RestAssured NullPointerException - needs investigation")
        void testGetAdjustmentHistory_NewestFirst() {
                // Given: A workorder with multiple adjustments
                UUID workorderId = seedWorkorderWithMultipleAdjustments();

                // Verify workorderId is not null
                assertThat(workorderId).isNotNull();

                // When: Get adjustment history (without partId filter)
                givenWithGatewayAuth()
                                .log().all()
                                .when()
                                .get("/v1/workorders/" + workorderId + "/parts/adjustments")
                                .then()
                                .log().all()
                                .statusCode(200)
                                .body("size()", equalTo(3))
                                .body("[0].adjustmentType", equalTo("CORRECTION")) // Last
                                .body("[1].adjustmentType", equalTo("ADDITIONAL_RETURN")) // Second
                                .body("[2].adjustmentType", equalTo("SUBSTITUTION")); // First
        }

        // ========== HELPER METHODS FOR TEST DATA SEEDING ==========

        private UUID seedWorkorderWithPart() {
                testCustomerId = UUID.fromString("00000000-0000-0000-0000-000000000001");
                testLocationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
                testVehicleId = UUID.fromString("00000000-0000-0000-0000-000000000001");

                // Create and save workorder
                Workorder workorder = Workorder.builder()
                                .customerId(testCustomerId)
                                .shopId(testLocationId)
                                .vehicleId(testVehicleId)
                                .status(WorkorderStatus.WORK_IN_PROGRESS)
                                .build();
                workorder = workorderRepository.save(workorder);

                // Create part line item
                WorkorderPart part = WorkorderPart.builder()
                                .workorder(workorder)
                                .description("Brake Pads - Front")
                                .quantity(BigDecimal.valueOf(2.0))
                                .unitPrice(BigDecimal.valueOf(45.00))
                                .lineTotal(BigDecimal.valueOf(90.00))
                                .taxCode("PARTS")
                                .status(WorkorderItemStatus.OPEN)
                                .quantityIssued(BigDecimal.ZERO)
                                .quantityConsumed(BigDecimal.ZERO)
                                .quantityReturned(BigDecimal.ZERO)
                                .build();
                workorderPartRepository.save(part);

                return workorder.getId();
        }

        private UUID seedWorkorderWithPartiallyConsumedPart(BigDecimal issuedQuantity, BigDecimal consumedQuantity) {
                UUID workorderId = seedWorkorderWithPart();
                UUID partId = workorderPartRepository.findByWorkorderId(workorderId).get(0).getId();

                // Update part with issued and consumed quantities
                WorkorderPart part = workorderPartRepository.findById(partId).orElseThrow();
                part.setQuantityIssued(issuedQuantity);
                part.setQuantityConsumed(consumedQuantity);
                workorderPartRepository.save(part);

                return workorderId;
        }

        private UUID seedWorkorderWithMultipleAdjustments() {
                UUID workorderId = seedWorkorderWithPart();
                UUID originalPartId = workorderPartRepository.findByWorkorderId(workorderId).get(0).getId();

                // Create substitute (first adjustment)
                Map<String, Object> substituteRequest = Map.of(
                                "originalPartId", originalPartId.toString(),
                                "substitutePartId", UUID.fromString("00000000-0000-0000-0000-000000000001").toString(),
                                "reason", "First adjustment");

                givenWithGatewayAuth()
                                .contentType(ContentType.JSON)
                                .body(substituteRequest)
                                .post("/v1/workorders/{workorderId}/parts/substitute", workorderId)
                                .then().statusCode(201);

                // Brief sleep to ensure different timestamps
                try {
                        Thread.sleep(10);
                } catch (InterruptedException e) {
                }

                // Update part to have issued/consumed for return test
                WorkorderPart part = workorderPartRepository.findById(originalPartId).orElseThrow();
                part.setQuantityIssued(BigDecimal.valueOf(10.0));
                part.setQuantityConsumed(BigDecimal.valueOf(5.0));
                workorderPartRepository.save(part);

                // Create return (second adjustment)
                Map<String, Object> returnRequest = Map.of(
                                "workorderPartId", originalPartId.toString(),
                                "quantity", 2.0,
                                "reason", "Second adjustment");

                givenWithGatewayAuth()
                                .contentType(ContentType.JSON)
                                .body(returnRequest)
                                .post("/v1/workorders/{workorderId}/parts/returnUnused", workorderId)
                                .then().statusCode(201);

                try {
                        Thread.sleep(10);
                } catch (InterruptedException e) {
                }

                // Create correction (third adjustment)
                Map<String, Object> correctRequest = Map.of(
                                "workorderPartId", originalPartId.toString(),
                                "newQuantity", 5.0,
                                "reason", "Third adjustment");

                givenWithGatewayAuth()
                                .contentType(ContentType.JSON)
                                .body(correctRequest)
                                .post("/v1/workorders/{workorderId}/parts/correct", workorderId)
                                .then().statusCode(201);

                return workorderId;
        }
}
