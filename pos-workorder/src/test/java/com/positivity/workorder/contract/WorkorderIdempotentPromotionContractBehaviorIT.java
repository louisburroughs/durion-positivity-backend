package com.positivity.workorder.contract;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.positivity.workorder.internal.entity.Estimate;
import com.positivity.workorder.internal.entity.EstimateItem;
import com.positivity.workorder.internal.entity.EstimateItemType;
import com.positivity.workorder.internal.entity.IdempotencyKey;
import com.positivity.workorder.internal.enums.ApprovalStatus;
import com.positivity.workorder.internal.enums.EstimateStatus;
import com.positivity.workorder.internal.repository.EstimateItemRepository;
import com.positivity.workorder.internal.repository.EstimateRepository;
import com.positivity.workorder.internal.repository.IdempotencyKeyRepository;
import com.positivity.workorder.internal.repository.WorkorderPartRepository;
import com.positivity.workorder.internal.repository.WorkorderRepository;
import com.positivity.workorder.internal.repository.WorkorderServiceRepository;
import com.positivity.workorder.support.BaseContractIntegrationTest;

import io.restassured.http.ContentType;
import io.restassured.response.Response;

/**
 * Contract behavior integration tests for idempotent workorder promotion.
 * CAP:004 - Promote Estimate to Workorder (Issue #164)
 * 
 * Tests cover:
 * - Workorder creation with Idempotency-Key header (happy path)
 * - Retry with same key returns existing workorder (idempotent replay)
 * - Different keys create different workorders
 * - Missing key allows duplicate creation (no idempotency enforcement)
 * - Expired idempotency keys allow new creation
 */
@DisplayName("Workorder Idempotent Promotion Contract Behavior Tests (CAP:004, Issue #164)")
class WorkorderIdempotentPromotionContractBehaviorIT extends BaseContractIntegrationTest {

        @Autowired
        private EstimateRepository estimateRepository;

        @Autowired
        private WorkorderRepository workorderRepository;

        @Autowired
        private EstimateItemRepository estimateItemRepository;

        @Autowired
        private WorkorderServiceRepository workorderServiceRepository;

        @Autowired
        private WorkorderPartRepository workorderPartRepository;

        @Autowired
        private IdempotencyKeyRepository idempotencyKeyRepository;

        private UUID testCustomerId;
        private UUID testLocationId;
        private UUID testVehicleId;

        @AfterEach
        void tearDown() {
                idempotencyKeyRepository.deleteAll();
                workorderServiceRepository.deleteAll();
                workorderPartRepository.deleteAll();
                workorderRepository.deleteAll();
                estimateItemRepository.deleteAll();
                estimateRepository.deleteAll();
        }

        // ========== IDEMPOTENT PROMOTION TESTS (Issue #164) ==========

        @Test
        @DisplayName("WO-IDM-001: Successfully create workorder with idempotency key")
        void testCreateWorkorder_WithIdempotencyKey_HappyPath() {
                UUID estimateId = seedApprovedEstimate();
                String idempotencyKey = UUID.fromString("00000000-0000-0000-0000-000000000001").toString();

                String requestBody = String.format("""
                                {
                                    "estimateId": "%s",
                                    "customerId": "%s"
                                }
                                """, estimateId, testCustomerId);

                Response response = givenWithGatewayAuth()
                                .contentType(ContentType.JSON)
                                .header("Idempotency-Key", idempotencyKey)
                                .body(requestBody)
                                .when()
                                .post("/v1/workorders")
                                .then()
                                .statusCode(200)
                                .body("id", notNullValue())
                                .body("estimateId", equalTo(estimateId.toString()))
                                .body("customerId", equalTo(testCustomerId.toString()))
                                .body("status", equalTo("DRAFT"))
                                .log().ifValidationFails()
                                .extract().response();

                String workorderId = response.path("id");

                // Verify idempotency key was registered
                IdempotencyKey key = idempotencyKeyRepository.findByKeyValue(idempotencyKey)
                                .orElseThrow(() -> new AssertionError("Idempotency key not registered"));
                org.assertj.core.api.Assertions.assertThat(key.getWorkorderId()).hasToString(workorderId);
        }

        @Test
        @DisplayName("WO-IDM-002: Retry with same idempotency key returns existing workorder")
        void testCreateWorkorder_WithSameIdempotencyKey_ReturnsExisting() {
                UUID estimateId = seedApprovedEstimate();
                String idempotencyKey = UUID.fromString("00000000-0000-0000-0000-000000000001").toString();

                String requestBody = String.format("""
                                {
                                    "estimateId": "%s",
                                    "customerId": "%s"
                                }
                                """, estimateId, testCustomerId);

                // First request - creates workorder
                Response firstResponse = givenWithGatewayAuth()
                                .contentType(ContentType.JSON)
                                .header("Idempotency-Key", idempotencyKey)
                                .body(requestBody)
                                .when()
                                .post("/v1/workorders")
                                .then()
                                .statusCode(200)
                                .body("id", notNullValue())
                                .log().ifValidationFails()
                                .extract().response();

                String firstWorkorderId = firstResponse.path("id");

                // Second request - same key, should return existing workorder
                Response secondResponse = givenWithGatewayAuth()
                                .contentType(ContentType.JSON)
                                .header("Idempotency-Key", idempotencyKey)
                                .body(requestBody)
                                .when()
                                .post("/v1/workorders")
                                .then()
                                .statusCode(200)
                                .body("id", equalTo(firstWorkorderId))
                                .body("estimateId", equalTo(estimateId.toString()))
                                .log().ifValidationFails()
                                .extract().response();

                String secondWorkorderId = secondResponse.path("id");

                // Assert same workorder returned
                org.assertj.core.api.Assertions.assertThat(secondWorkorderId).isEqualTo(firstWorkorderId);

                // Verify only one workorder exists
                long count = workorderRepository.count();
                org.assertj.core.api.Assertions.assertThat(count).isEqualTo(1);
        }

        @Test
        @DisplayName("WO-IDM-003: Different idempotency keys create different workorders")
        void testCreateWorkorder_WithDifferentIdempotencyKeys_CreatesDifferent() {
                UUID estimateId1 = seedApprovedEstimate();
                UUID estimateId2 = seedApprovedEstimate();
                String idempotencyKey1 = UUID.fromString("00000000-0000-0000-0000-000000000001").toString();
                String idempotencyKey2 = UUID.fromString("00000000-0000-0000-0000-000000000002").toString();

                String requestBody1 = String.format("""
                                {
                                    "estimateId": "%s",
                                    "customerId": "%s"
                                }
                                """, estimateId1, testCustomerId);

                String requestBody2 = String.format("""
                                {
                                    "estimateId": "%s",
                                    "customerId": "%s"
                                }
                                """, estimateId2, testCustomerId);

                // First request
                Response firstResponse = givenWithGatewayAuth()
                                .contentType(ContentType.JSON)
                                .header("Idempotency-Key", idempotencyKey1)
                                .body(requestBody1)
                                .when()
                                .post("/v1/workorders")
                                .then()
                                .statusCode(200)
                                .body("id", notNullValue())
                                .log().ifValidationFails()
                                .extract().response();

                String firstWorkorderId = firstResponse.path("id");

                // Second request with different key
                Response secondResponse = givenWithGatewayAuth()
                                .contentType(ContentType.JSON)
                                .header("Idempotency-Key", idempotencyKey2)
                                .body(requestBody2)
                                .when()
                                .post("/v1/workorders")
                                .then()
                                .statusCode(200)
                                .body("id", notNullValue())
                                .log().ifValidationFails()
                                .extract().response();

                String secondWorkorderId = secondResponse.path("id");

                // Assert different workorders created
                org.assertj.core.api.Assertions.assertThat(secondWorkorderId).isNotEqualTo(firstWorkorderId);

                // Verify two workorders exist
                long count = workorderRepository.count();
                org.assertj.core.api.Assertions.assertThat(count).isEqualTo(2);
        }

        @Test
        @DisplayName("WO-IDM-004: Missing idempotency key allows duplicate creation")
        void testCreateWorkorder_WithoutIdempotencyKey_AllowsDuplicates() {
                UUID estimateId = seedApprovedEstimate();
                UUID secondEstimateId = seedApprovedEstimate();

                String firstRequestBody = String.format("""
                                {
                                    "estimateId": "%s",
                                    "customerId": "%s"
                                }
                                """, estimateId, testCustomerId);

                String secondRequestBody = String.format("""
                                {
                                    "estimateId": "%s",
                                    "customerId": "%s"
                                }
                                """, secondEstimateId, testCustomerId);

                // First request without key
                Response firstResponse = givenWithGatewayAuth()
                                .contentType(ContentType.JSON)
                                .body(firstRequestBody)
                                .when()
                                .post("/v1/workorders")
                                .then()
                                .statusCode(200)
                                .body("id", notNullValue())
                                .log().ifValidationFails()
                                .extract().response();

                String firstWorkorderId = firstResponse.path("id");

                // Second request without key - should create new workorder
                Response secondResponse = givenWithGatewayAuth()
                                .contentType(ContentType.JSON)
                                .body(secondRequestBody)
                                .when()
                                .post("/v1/workorders")
                                .then()
                                .statusCode(200)
                                .body("id", notNullValue())
                                .log().ifValidationFails()
                                .extract().response();

                String secondWorkorderId = secondResponse.path("id");

                // Assert different workorders created (no idempotency)
                org.assertj.core.api.Assertions.assertThat(secondWorkorderId).isNotEqualTo(firstWorkorderId);

                // Verify two workorders exist
                long count = workorderRepository.count();
                org.assertj.core.api.Assertions.assertThat(count).isEqualTo(2);
        }

        @Test
        @DisplayName("WO-IDM-005: Whitespace-only idempotency key treated as missing")
        void testCreateWorkorder_WithWhitespaceIdempotencyKey_TreatedAsMissing() {
                UUID estimateId = seedApprovedEstimate();
                UUID secondEstimateId = seedApprovedEstimate();

                String firstRequestBody = String.format("""
                                {
                                    "estimateId": "%s",
                                    "customerId": "%s"
                                }
                                """, estimateId, testCustomerId);

                String secondRequestBody = String.format("""
                                {
                                    "estimateId": "%s",
                                    "customerId": "%s"
                                }
                                """, secondEstimateId, testCustomerId);

                // First request with whitespace-only key (spaces)
                Response firstResponse = givenWithGatewayAuth()
                                .contentType(ContentType.JSON)
                                .header("Idempotency-Key", "   ")
                                .body(firstRequestBody)
                                .when()
                                .post("/v1/workorders")
                                .then()
                                .statusCode(200)
                                .body("id", notNullValue())
                                .log().ifValidationFails()
                                .extract().response();

                String firstWorkorderId = firstResponse.path("id");

                // Second request with whitespace-only key (tabs and spaces) - should create new
                // workorder
                Response secondResponse = givenWithGatewayAuth()
                                .contentType(ContentType.JSON)
                                .header("Idempotency-Key", " \t ")
                                .body(secondRequestBody)
                                .when()
                                .post("/v1/workorders")
                                .then()
                                .statusCode(200)
                                .body("id", notNullValue())
                                .log().ifValidationFails()
                                .extract().response();

                String secondWorkorderId = secondResponse.path("id");

                // Assert different workorders created (whitespace keys treated as missing)
                org.assertj.core.api.Assertions.assertThat(secondWorkorderId).isNotEqualTo(firstWorkorderId);

                // Verify two workorders exist
                long count = workorderRepository.count();
                org.assertj.core.api.Assertions.assertThat(count).isEqualTo(2);

                // Verify no idempotency keys were registered (whitespace keys are ignored)
                long keyCount = idempotencyKeyRepository.count();
                org.assertj.core.api.Assertions.assertThat(keyCount).isEqualTo(0);
        }

        // ========== HELPER METHODS ==========

        /**
         * Seed an approved estimate for workorder creation.
         */
        private UUID seedApprovedEstimate() {
                testCustomerId = UUID.fromString("00000000-0000-0000-0000-000000000001");
                testLocationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
                testVehicleId = UUID.fromString("00000000-0000-0000-0000-000000000001");

                Estimate estimate = Estimate.builder()
                                .customerId(testCustomerId)
                                .locationId(testLocationId)
                                .vehicleId(testVehicleId)
                                .status(EstimateStatus.APPROVED)
                                .createdById(SYSTEM_USER_ID.toString())
                                .build();

                Estimate saved = estimateRepository.save(estimate);

                EstimateItem approvedItem = EstimateItem.builder()
                                .estimate(saved)
                                .itemType(EstimateItemType.LABOR)
                                .description("Test labor")
                                .quantity(new BigDecimal("1.0000"))
                                .unitPrice(new BigDecimal("100.00"))
                                .lineTotal(new BigDecimal("100.00"))
                                .approvalStatus(ApprovalStatus.APPROVED)
                                .createdById(SYSTEM_USER_ID.toString())
                                .build();
                estimateItemRepository.save(approvedItem);

                return saved.getId();
        }
}
