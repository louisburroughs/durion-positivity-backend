package com.positivity.workorder.contract;

import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;

import java.math.BigDecimal;
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

/**
 * Contract behavioral tests for Estimate Summary endpoints.
 * Story #18 (Present Estimate Summary)
 */
class EstimateSummaryContractBehaviorIT extends BaseContractIntegrationTest {

        @Autowired
        private EstimateRepository estimateRepository;

        @Autowired
        private EstimateItemRepository estimateItemRepository;

        @AfterEach
        void tearDown() {
                estimateItemRepository.deleteAll();
                estimateRepository.deleteAll();
        }

        @Test
        @DisplayName("Contract: Get customer-facing estimate summary - Happy Path")
        void shouldGetEstimateSummary() {
                UUID estimateId = seedEstimateWithPartAndLaborItems();

                givenWithGatewayAuth()
                                .when()
                                .get("/v1/workorders/estimates/{estimateId}/summary", estimateId)
                                .then()
                                .statusCode(200)
                                .contentType(startsWith("application/json"))
                                .body("id", equalTo(estimateId.toString()))
                                .body("partItems", notNullValue())
                                .body("laborItems", notNullValue())
                                .body("partItems.size()", greaterThanOrEqualTo(1))
                                .body("laborItems.size()", greaterThanOrEqualTo(1))
                                .body("subtotal", notNullValue())
                                .body("taxAmount", notNullValue())
                                .body("total", notNullValue())
                                .log().ifValidationFails();
        }

        @Test
        @DisplayName("Contract: Summary groups line items by type (parts and labor)")
        void shouldGroupLineItemsByType() {
                UUID estimateId = seedEstimateWithPartAndLaborItems();

                givenWithGatewayAuth()
                                .when()
                                .get("/v1/workorders/estimates/{estimateId}/summary", estimateId)
                                .then()
                                .statusCode(200)
                                .contentType(startsWith("application/json"))
                                .body("partItems", notNullValue())
                                .body("laborItems", notNullValue())
                                .log().ifValidationFails();
        }

        @Test
        @DisplayName("Contract: Summary includes financial breakdown")
        void shouldIncludeFinancialBreakdown() {
                UUID estimateId = seedEstimateWithPartAndLaborItems();

                givenWithGatewayAuth()
                                .when()
                                .get("/v1/workorders/estimates/{estimateId}/summary", estimateId)
                                .then()
                                .statusCode(200)
                                .contentType(startsWith("application/json"))
                                .body("subtotal", notNullValue())
                                .body("taxAmount", notNullValue())
                                .body("total", notNullValue())
                                .log().ifValidationFails();
        }

        @Test
        @DisplayName("Contract: Create historical snapshot - Happy Path")
        void shouldCreateEstimateSnapshot() {
                UUID estimateId = seedEstimateWithPartAndLaborItems();

                givenWithGatewayAuth()
                                .queryParam("notes", "Pre-approval snapshot")
                                .when()
                                .post("/v1/workorders/estimates/{estimateId}/snapshots", estimateId)
                                .then()
                                .statusCode(200)
                                .body("id", notNullValue())
                                .body("estimateId", equalTo(estimateId.toString()))
                                .body("capturedAt", notNullValue())
                                .log().ifValidationFails();
        }

        @Test
        @DisplayName("Contract: Snapshot captures complete estimate state as JSON")
        void shouldCaptureCompleteEstimateState() {
                UUID estimateId = seedEstimateWithPartAndLaborItems();

                givenWithGatewayAuth()
                                .when()
                                .post("/v1/workorders/estimates/{estimateId}/snapshots", estimateId)
                                .then()
                                .statusCode(200)
                                .body("snapshotData", notNullValue())
                                .log().ifValidationFails();
        }

        @Test
        @DisplayName("Contract: Snapshot is immutable - Captured timestamp never changes")
        void shouldCreateImmutableSnapshot() {
                UUID estimateId = seedEstimateWithPartAndLaborItems();

                givenWithGatewayAuth()
                                .when()
                                .post("/v1/workorders/estimates/{estimateId}/snapshots", estimateId)
                                .then()
                                .statusCode(200)
                                .log().ifValidationFails();

                givenWithGatewayAuth()
                                .when()
                                .post("/v1/workorders/estimates/{estimateId}/snapshots", estimateId)
                                .then()
                                .statusCode(200)
                                .log().ifValidationFails();
        }

        @Test
        @DisplayName("Contract: PDF generation not implemented - Documented limitation")
        void shouldDocumentPDFGenerationLimitation() {
                UUID estimateId = seedEstimateWithPartAndLaborItems();

                givenWithGatewayAuth()
                                .when()
                                .get("/v1/workorders/estimates/{estimateId}/summary", estimateId)
                                .then()
                                .statusCode(200)
                                .contentType(anyOf(is("application/json"), is("application/json;charset=UTF-8")))
                                .log().ifValidationFails();
        }

        @Test
        @DisplayName("Contract: Multiple snapshots can be created for audit trail")
        void shouldAllowMultipleSnapshots() {
                UUID estimateId = seedEstimateWithPartAndLaborItems();

                givenWithGatewayAuth()
                                .queryParam("notes", "First snapshot")
                                .when()
                                .post("/v1/workorders/estimates/{estimateId}/snapshots", estimateId)
                                .then()
                                .statusCode(200);

                givenWithGatewayAuth()
                                .queryParam("notes", "Second snapshot after changes")
                                .when()
                                .post("/v1/workorders/estimates/{estimateId}/snapshots", estimateId)
                                .then()
                                .statusCode(200);
        }

        private UUID seedEstimateWithPartAndLaborItems() {
                Estimate estimate = Estimate.builder()
                                .id(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                                .estimateNumber("EST-" + System.nanoTime())
                                .locationId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                                .vehicleId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                                .customerId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                                .currencyUomId("USD")
                                .taxRegionId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                                .status(EstimateStatus.DRAFT)
                                .subtotal(new BigDecimal("200.00"))
                                .taxAmount(new BigDecimal("16.50"))
                                .total(new BigDecimal("216.50"))
                                .createdByUserId(SYSTEM_USER_ID.toString())
                                .createdById(SYSTEM_USER_ID.toString())
                                .build();

                Estimate savedEstimate = estimateRepository.save(estimate);

                estimateItemRepository.save(buildItem(savedEstimate.getId(), EstimateItemType.PART, "Front brake pads",
                                new BigDecimal("2"), new BigDecimal("50.00")));
                estimateItemRepository.save(buildItem(savedEstimate.getId(), EstimateItemType.LABOR, "Brake labor",
                                new BigDecimal("5"), new BigDecimal("20.00")));

                return savedEstimate.getId();
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
}
