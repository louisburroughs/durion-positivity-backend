package com.positivity.workorder.contract;

import java.time.ZoneOffset;
import java.time.Instant;
import java.time.Clock;

import static org.hamcrest.Matchers.emptyIterable;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.positivity.workorder.internal.entity.Estimate;
import com.positivity.workorder.internal.entity.EstimateItem;
import com.positivity.workorder.internal.entity.EstimateItemType;
import com.positivity.workorder.internal.enums.ApprovalStatus;
import com.positivity.workorder.internal.enums.EstimateStatus;
import com.positivity.workorder.internal.repository.EstimateItemRepository;
import com.positivity.workorder.internal.repository.EstimateRepository;
import com.positivity.workorder.internal.repository.WorkorderRepository;
import com.positivity.workorder.support.BaseContractIntegrationTest;

import io.restassured.http.ContentType;

/**
 * Integration tests for Story #93 (CAP-094): Emit CRM Reference IDs in
 * Workorder Artifacts.
 *
 * <p>
 * Verifies that {@code crmPartyId}, {@code crmVehicleId}, and
 * {@code crmContactIds} are
 * correctly accepted on Estimate creation, persisted to the database, returned
 * in the response,
 * and propagated to Workorder entities upon conversion.
 *
 * <p>
 * Covers:
 * <ul>
 * <li>AC-1: Estimate creation with CRM IDs — fields persisted and returned in
 * 201 response</li>
 * <li>AC-1b: Empty {@code crmContactIds = []} is valid</li>
 * <li>AC-2: Estimate-to-Workorder conversion propagates CRM IDs</li>
 * <li>AC-3: Missing required CRM fields return 400 Bad Request</li>
 * </ul>
 *
 * @see com.positivity.workorder.internal.service.EstimateServiceImpl
 * @see com.positivity.workorder.internal.service.WorkorderServiceImpl
 */
@DisplayName("CAP-094 Story #93: CRM Reference ID Contract Behavior Tests")
class CrmReferenceIdContractBehaviorIT extends BaseContractIntegrationTest {
        private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);

        private static final String CRM_PARTY_ID = "01952f4e-a001-7000-8000-crm0000001";
        private static final String CRM_VEHICLE_ID = "01952f4e-a002-7000-8000-crm0000002";
        private static final String CRM_CONTACT_ID = "01952f4e-a003-7000-8000-crm0000003";

        @Autowired
        private EstimateRepository estimateRepository;

        @Autowired
        private EstimateItemRepository estimateItemRepository;

        // @Autowired
        // private WorkorderRepository workorderRepository;

        @AfterEach
        void tearDown() {
                purgeTestData();
        }

        // =====================================================================
        // AC-1: Estimate creation persists and returns CRM IDs
        // =====================================================================

        @Test
        @DisplayName("AC-1: Create estimate — crmPartyId is persisted and returned in response")
        void ac1_createEstimate_crmPartyId_persistedAndReturned() {
                String payload = buildEstimatePayload(CRM_PARTY_ID, CRM_VEHICLE_ID, List.of(CRM_CONTACT_ID));

                givenWithGatewayAuth()
                                .contentType(ContentType.JSON)
                                .body(payload)
                                .when()
                                .post("/v1/workorders/estimates")
                                .then()
                                .log().ifValidationFails()
                                .statusCode(201)
                                .body("crmPartyId", equalTo(CRM_PARTY_ID));
        }

        @Test
        @DisplayName("AC-1: Create estimate — crmVehicleId is persisted and returned in response")
        void ac1_createEstimate_crmVehicleId_persistedAndReturned() {
                String payload = buildEstimatePayload(CRM_PARTY_ID, CRM_VEHICLE_ID, List.of(CRM_CONTACT_ID));

                givenWithGatewayAuth()
                                .contentType(ContentType.JSON)
                                .body(payload)
                                .when()
                                .post("/v1/workorders/estimates")
                                .then()
                                .log().ifValidationFails()
                                .statusCode(201)
                                .body("crmVehicleId", equalTo(CRM_VEHICLE_ID));
        }

        @Test
        @DisplayName("AC-1: Create estimate — crmContactIds are persisted and returned in response")
        void ac1_createEstimate_crmContactIds_persistedAndReturned() {
                String payload = buildEstimatePayload(CRM_PARTY_ID, CRM_VEHICLE_ID, List.of(CRM_CONTACT_ID));

                givenWithGatewayAuth()
                                .contentType(ContentType.JSON)
                                .body(payload)
                                .when()
                                .post("/v1/workorders/estimates")
                                .then()
                                .log().ifValidationFails()
                                .statusCode(201)
                                .body("crmContactIds", hasItem(CRM_CONTACT_ID));
        }

        @Test
        @DisplayName("AC-1: GET on created estimate returns persisted crmPartyId")
        void ac1_getEstimate_crmPartyId_persistedAcrossGetRequest() {
                String payload = buildEstimatePayload(CRM_PARTY_ID, CRM_VEHICLE_ID, List.of(CRM_CONTACT_ID));

                // Create estimate and get its ID
                String estimateId = givenWithGatewayAuth()
                                .contentType(ContentType.JSON)
                                .body(payload)
                                .when()
                                .post("/v1/workorders/estimates")
                                .then()
                                .statusCode(201)
                                .extract()
                                .path("id");

                givenWithGatewayAuth()
                                .when()
                                .get("/v1/workorders/estimates/{id}", estimateId)
                                .then()
                                .log().ifValidationFails()
                                .statusCode(200)
                                .body("crmPartyId", equalTo(CRM_PARTY_ID))
                                .body("crmVehicleId", equalTo(CRM_VEHICLE_ID));
        }

        // =====================================================================
        // AC-1b: Empty crmContactIds is valid
        // =====================================================================

        @Test
        @DisplayName("AC-1b: Create estimate with empty crmContactIds — 201 with empty array returned")
        void ac1b_createEstimate_emptyCrmContactIds_isAccepted() {
                String payload = buildEstimatePayload(CRM_PARTY_ID, CRM_VEHICLE_ID, List.of());

                givenWithGatewayAuth()
                                .contentType(ContentType.JSON)
                                .body(payload)
                                .when()
                                .post("/v1/workorders/estimates")
                                .then()
                                .log().ifValidationFails()
                                .statusCode(201)
                                .body("crmPartyId", equalTo(CRM_PARTY_ID))
                                .body("crmContactIds", emptyIterable());
        }

        // =====================================================================
        // AC-2: Workorder conversion preserves CRM IDs
        // =====================================================================

        @Test
        @DisplayName("AC-2: Promote estimate to workorder — workorder response contains crmPartyId")
        void ac2_promoteEstimate_workorder_containsCrmPartyId() {
                // Seed an APPROVED estimate directly with CRM fields already set, to isolate
                // the workorder-promotion behaviour from the (also broken) create-estimate
                // path.
                UUID estimateId = seedApprovedEstimateWithCrmFields(CRM_PARTY_ID, CRM_VEHICLE_ID,
                                List.of(CRM_CONTACT_ID));

                givenWithGatewayAuth()
                                .when()
                                .post("/v1/workorders/estimates/{id}/promote", estimateId)
                                .then()
                                .log().ifValidationFails()
                                .statusCode(200)
                                .body("crmPartyId", equalTo(CRM_PARTY_ID));
        }

        @Test
        @DisplayName("AC-2: Promote estimate to workorder — workorder response contains crmVehicleId")
        void ac2_promoteEstimate_workorder_containsCrmVehicleId() {
                UUID estimateId = seedApprovedEstimateWithCrmFields(CRM_PARTY_ID, CRM_VEHICLE_ID,
                                List.of(CRM_CONTACT_ID));

                givenWithGatewayAuth()
                                .when()
                                .post("/v1/workorders/estimates/{id}/promote", estimateId)
                                .then()
                                .log().ifValidationFails()
                                .statusCode(200)
                                .body("crmVehicleId", equalTo(CRM_VEHICLE_ID));
        }

        @Test
        @DisplayName("AC-2: Promote estimate to workorder — workorder response contains crmContactIds")
        void ac2_promoteEstimate_workorder_containsCrmContactIds() {
                UUID estimateId = seedApprovedEstimateWithCrmFields(CRM_PARTY_ID, CRM_VEHICLE_ID,
                                List.of(CRM_CONTACT_ID));

                givenWithGatewayAuth()
                                .when()
                                .post("/v1/workorders/estimates/{id}/promote", estimateId)
                                .then()
                                .log().ifValidationFails()
                                .statusCode(200)
                                .body("crmContactIds", hasItem(CRM_CONTACT_ID));
        }

        // =====================================================================
        // AC-3: Missing required CRM fields return 400
        // These assertions verify that Bean Validation (@NotBlank) is already
        // in place. They are GREEN by scaffold — that is intentional.
        // =====================================================================

        @Test
        @DisplayName("AC-3: POST without crmVehicleId — 400 Bad Request")
        void ac3_createEstimate_missingCrmVehicleId_returns400() {
                String payloadWithoutCrmVehicleId = buildEstimatePayloadNoVehicle(CRM_PARTY_ID);

                givenWithGatewayAuth()
                                .contentType(ContentType.JSON)
                                .body(payloadWithoutCrmVehicleId)
                                .when()
                                .post("/v1/workorders/estimates")
                                .then()
                                .log().ifValidationFails()
                                .statusCode(400);
        }

        @Test
        @DisplayName("AC-3b: POST without crmPartyId — 400 Bad Request")
        void ac3b_createEstimate_missingCrmPartyId_returns400() {
                String payloadWithoutCrmPartyId = buildEstimatePayloadNoParty(CRM_VEHICLE_ID);

                givenWithGatewayAuth()
                                .contentType(ContentType.JSON)
                                .body(payloadWithoutCrmPartyId)
                                .when()
                                .post("/v1/workorders/estimates")
                                .then()
                                .log().ifValidationFails()
                                .statusCode(400);
        }

        // =====================================================================
        // Helpers
        // =====================================================================

        /**
         * Build a valid CreateEstimateRequest JSON payload with supplied CRM IDs.
         */
        private static String buildEstimatePayload(String crmPartyId, String crmVehicleId,
                        List<String> crmContactIds) {
                UUID customerId = UUID.nameUUIDFromBytes("CUST-CRM-001".getBytes());
                UUID vehicleId = UUID.nameUUIDFromBytes("VEH-CRM-001".getBytes());
                UUID locationId = UUID.nameUUIDFromBytes("LOC-CRM-001".getBytes());

                String contactIdsJson = crmContactIds.isEmpty() ? "[]"
                                : "[\"" + String.join("\",\"", crmContactIds) + "\"]";

                return String.format(
                                "{\"customerId\":\"%s\",\"vehicleId\":\"%s\",\"locationId\":\"%s\","
                                                + "\"subtotal\":500.00,\"taxAmount\":50.00,\"total\":550.00,"
                                                + "\"crmPartyId\":\"%s\","
                                                + "\"crmVehicleId\":\"%s\","
                                                + "\"crmContactIds\":%s}",
                                customerId, vehicleId, locationId,
                                crmPartyId, crmVehicleId, contactIdsJson);
        }

        /**
         * Payload omitting the crmVehicleId field entirely (for AC-3 validation test).
         */
        private static String buildEstimatePayloadNoVehicle(String crmPartyId) {
                UUID customerId = UUID.nameUUIDFromBytes("CUST-CRM-VAL-001".getBytes());
                UUID vehicleId = UUID.nameUUIDFromBytes("VEH-CRM-VAL-001".getBytes());
                UUID locationId = UUID.nameUUIDFromBytes("LOC-CRM-VAL-001".getBytes());

                return String.format(
                                "{\"customerId\":\"%s\",\"vehicleId\":\"%s\",\"locationId\":\"%s\","
                                                + "\"subtotal\":100.00,\"taxAmount\":10.00,\"total\":110.00,"
                                                + "\"crmPartyId\":\"%s\","
                                                + "\"crmContactIds\":[]}",
                                customerId, vehicleId, locationId, crmPartyId);
        }

        /**
         * Payload omitting the crmPartyId field entirely (for AC-3b validation test).
         */
        private static String buildEstimatePayloadNoParty(String crmVehicleId) {
                UUID customerId = UUID.nameUUIDFromBytes("CUST-CRM-VAL-002".getBytes());
                UUID vehicleId = UUID.nameUUIDFromBytes("VEH-CRM-VAL-002".getBytes());
                UUID locationId = UUID.nameUUIDFromBytes("LOC-CRM-VAL-002".getBytes());

                return String.format(
                                "{\"customerId\":\"%s\",\"vehicleId\":\"%s\",\"locationId\":\"%s\","
                                                + "\"subtotal\":100.00,\"taxAmount\":10.00,\"total\":110.00,"
                                                + "\"crmVehicleId\":\"%s\","
                                                + "\"crmContactIds\":[]}",
                                customerId, vehicleId, locationId, crmVehicleId);
        }

        /**
         * Seed an APPROVED estimate directly into the repository, with CRM fields
         * already set and an approved item present, so the promote endpoint can be
         * called without needing to go through the (currently broken) create + approve
         * API flow.
         */
        private UUID seedApprovedEstimateWithCrmFields(String crmPartyId, String crmVehicleId,
                        List<String> crmContactIds) {
                UUID customerId = UUID.nameUUIDFromBytes("CUST-CRM-PROMO-001".getBytes());
                UUID vehicleId = UUID.nameUUIDFromBytes("VEH-CRM-PROMO-001".getBytes());
                UUID locationId = UUID.nameUUIDFromBytes("LOC-CRM-PROMO-001".getBytes());

                Estimate estimate = Estimate.builder()
                                .estimateNumber("EST-CRM-" + UUID.fromString("00000000-0000-0000-0000-000000000001")
                                                .toString().substring(0, 8))
                                .customerId(customerId)
                                .vehicleId(vehicleId)
                                .locationId(locationId)
                                .status(EstimateStatus.APPROVED)
                                .approvedAt(LocalDateTime.now(TEST_CLOCK).minusHours(1))
                                .approvedBy(customerId)
                                .expiresAt(LocalDateTime.now(TEST_CLOCK).plusDays(30))
                                .subtotal(new BigDecimal("200.00"))
                                .taxAmount(new BigDecimal("20.00"))
                                .total(new BigDecimal("220.00"))
                                .currencyUomId("USD")
                                .createdByUserId("test-user")
                                .createdById("test-user")
                                .crmPartyId(crmPartyId)
                                .crmVehicleId(crmVehicleId)
                                .crmContactIds(crmContactIds)
                                .build();
                estimate = estimateRepository.save(estimate);

                // An approved line item is required for the promote precondition check.
                EstimateItem item = EstimateItem.builder()
                                .estimate(estimate)
                                .itemType(EstimateItemType.LABOR)
                                .description("Test labor — CRM reference ID story")
                                .quantity(new BigDecimal("1.0000"))
                                .unitPrice(new BigDecimal("200.00"))
                                .lineTotal(new BigDecimal("200.00"))
                                .taxCode("LABOR_TAX")
                                .approvalStatus(ApprovalStatus.APPROVED)
                                .approvalTimestamp(LocalDateTime.now(TEST_CLOCK).minusHours(1))
                                .createdById("test-user")
                                .build();
                estimateItemRepository.save(item);

                return estimate.getId();
        }
}
