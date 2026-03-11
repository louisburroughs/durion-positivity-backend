package com.positivity.workorder.contract;

import java.time.ZoneOffset;
import java.time.Clock;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import com.positivity.workorder.internal.entity.TravelSegment;
import com.positivity.workorder.internal.enums.TravelSegmentStatus;
import com.positivity.workorder.internal.enums.TravelSegmentType;
import com.positivity.workorder.internal.repository.TravelSegmentRepository;
import com.positivity.workorder.support.BaseContractIntegrationTest;

import io.restassured.http.ContentType;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Contract behavior integration tests for Mobile Travel Segment Capture
 * (CAP-139 Story #67).
 *
 * <p>
 * Verifies REST contract wiring for travel segment lifecycle:
 * start, stop, submit, and adjustment creation.
 * GREEN phase: service implementations are active, so endpoints return
 * domain-correct HTTP codes. The 400 test confirms Bean Validation is
 * enforced before the service is reached.
 * </p>
 *
 * <p>
 * Endpoints under test:
 * </p>
 * <ul>
 * <li>POST /v1/workorders/travelSegments/start → 201</li>
 * <li>POST /v1/workorders/travelSegments/start → 400 (missing required
 * fields)</li>
 * <li>POST /v1/workorders/travelSegments/{id}/stop → 404 (non-existent id)</li>
 * <li>POST /v1/workorders/travelSegments/submit/{mobileWorkAssignmentId} → 404
 * (no segments)</li>
 * <li>POST /v1/workorders/travelSegments/{id}/adjustments → 404 (non-existent
 * id)</li>
 * <li>POST /v1/workorders/travelSegments/start → 409 (TRAVEL_SEGMENT_CONFLICT,
 * already active)</li>
 * <li>POST /v1/workorders/travelSegments/{id}/adjustments → 409
 * (TRAVEL_SEGMENT_CONFLICT, non-APPROVED)</li>
 * </ul>
 *
 * Issue: CAP-139 Story #67
 */
@DisplayName("TravelSegment Contract Behavior Tests — GREEN phase (CAP-139 Story #67)")
@Import(ContractTestConfiguration.class)
class TravelSegmentContractBehaviorIT extends BaseContractIntegrationTest {
        private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);

        @Autowired
        private TravelSegmentRepository travelSegmentRepository;

        @AfterEach
        void tearDown() {
                purgeTestData();
        }

        // ── Fixtures ─────────────────────────────────────────────────────────────

        private static final UUID MOBILE_WORK_ASSIGNMENT_ID = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
        private static final UUID TECHNICIAN_ID = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");

        private Map<String, Object> validStartBody() {
                return Map.of(
                                "mobileWorkAssignmentId", MOBILE_WORK_ASSIGNMENT_ID.toString(),
                                "technicianId", TECHNICIAN_ID.toString(),
                                "segmentType", "DEPART_SHOP");
        }

        // ── TSC-001: Start travel segment → 201 (GREEN phase) ────────────────────

        /**
         * TSC-001 — POST /start with all required fields returns HTTP 201 in GREEN
         * phase because the service creates an IN_PROGRESS travel segment.
         */
        @Test
        @DisplayName("TSC-001: POST /start with valid body returns 201")
        void TSC001_startTravelSegment_returns201() {
                // Issue CAP-139 Story #67: AC1 — start endpoint creates segment
                givenWithGatewayAuth()
                                .contentType(ContentType.JSON)
                                .body(validStartBody())
                                .when()
                                .post("/v1/workorders/travelSegments/start")
                                .then()
                                .log().ifValidationFails()
                                .statusCode(201)
                                .body("travelSegmentId", notNullValue());
        }

        // ── TSC-002: Missing required fields → 400 ────────────────────────────────

        /**
         * TSC-002 — POST /start with an empty body triggers Bean Validation and
         * returns HTTP 400 before the stub is invoked.
         * This validates that request constraints are enforced at the controller.
         */
        @Test
        @DisplayName("TSC-002: POST /start with empty body returns 400 (Bean Validation)")
        void TSC002_startTravelSegment_missingRequired_returns400() {
                // Issue CAP-139 Story #67: Bean Validation wired before reaching service stub
                givenWithGatewayAuth()
                                .contentType(ContentType.JSON)
                                .body("{}")
                                .when()
                                .post("/v1/workorders/travelSegments/start")
                                .then()
                                .log().ifValidationFails()
                                .statusCode(400)
                                .body("code", equalTo("VALIDATION_FAILED"))
                                .body("status", equalTo(400))
                                .body("correlationId", notNullValue());
        }

        // ── TSC-003: Stop non-existent segment → 404 (GREEN phase) ──────────────

        /**
         * TSC-003 — POST /{id}/stop with a non-existent travelSegmentId returns HTTP
         * 404
         * because the service raises a not-found domain exception.
         */
        @Test
        @DisplayName("TSC-003: POST /{id}/stop with non-existent id returns 404")
        void TSC003_stopTravelSegment_nonExistent_returns404() {
                // Issue CAP-139 Story #67: AC4 — stop endpoint raises not-found for unknown id
                UUID randomSegmentId = UUID.fromString("00000000-0000-0000-0000-000000000001");

                givenWithGatewayAuth()
                                .contentType(ContentType.JSON)
                                .body("{}")
                                .when()
                                .post("/v1/workorders/travelSegments/{travelSegmentId}/stop", randomSegmentId)
                                .then()
                                .log().ifValidationFails()
                                .statusCode(404);
        }

        // ── TSC-004: Submit travel segments → 404 when empty (GREEN phase) ───────

        /**
         * TSC-004 — POST /submit/{mobileWorkAssignmentId} with no existing segments
         * returns HTTP 404 because the service raises a not-found domain exception.
         */
        @Test
        @DisplayName("TSC-004: POST /submit/{id} with no segments returns 404")
        void TSC004_submitTravelSegments_noSegments_returns404() {
                // Issue CAP-139 Story #67: AC6 — submit raises not-found when no segments exist
                // N-01 fix: use a random UUID to ensure no segments exist regardless of test
                // execution order
                UUID freshAssignmentId = UUID.fromString("00000000-0000-0000-0000-000000000001");
                givenWithGatewayAuth()
                                .contentType(ContentType.JSON)
                                .body(Map.of("workDate", "2025-01-15"))
                                .when()
                                .post("/v1/workorders/travelSegments/submit/{mobileWorkAssignmentId}",
                                                freshAssignmentId)
                                .then()
                                .log().ifValidationFails()
                                .statusCode(404);
        }

        // ── TSC-006: Conflict on second start for same assignment ─────────────────

        /**
         * TSC-006 — POST /start with an already-active segment returns HTTP 409
         * with error code TRAVEL_SEGMENT_CONFLICT.
         */
        @Test
        @DisplayName("TSC-006: POST /start with active segment returns 409 (TRAVEL_SEGMENT_CONFLICT)")
        void TSC006_startTravelSegment_conflictWhenActive_returns409() {
                // AC3: CAP-139 Story #67 — second start on same assignment returns 409
                UUID freshAssignmentId = UUID.fromString("00000000-0000-0000-0000-000000000001");
                Map<String, Object> body = Map.of(
                                "mobileWorkAssignmentId", freshAssignmentId.toString(),
                                "technicianId", UUID.fromString("00000000-0000-0000-0000-000000000001").toString(),
                                "segmentType", "DEPART_SHOP");

                // Step 1: first start succeeds
                givenWithGatewayAuth()
                                .body(body)
                                .when().post("/v1/workorders/travelSegments/start")
                                .then().statusCode(201);

                // Step 2: second start on same assignment returns 409
                givenWithGatewayAuth()
                                .body(body)
                                .when().post("/v1/workorders/travelSegments/start")
                                .then()
                                .statusCode(409)
                                .body("code", equalTo("TRAVEL_SEGMENT_CONFLICT"));
        }

        // ── TSC-007: Adjustment on non-APPROVED segment → 409 ────────────────────

        /**
         * TSC-007 — POST /{id}/adjustments on an IN_PROGRESS segment returns HTTP 409
         * with error code TRAVEL_SEGMENT_CONFLICT.
         */
        @Test
        @DisplayName("TSC-007: POST /{id}/adjustments on non-APPROVED segment returns 409 (TRAVEL_SEGMENT_CONFLICT)")
        void TSC007_createAdjustment_onNonApprovedSegment_returns409() {
                // AC8: CAP-139 Story #67 — adjustment on IN_PROGRESS segment returns 409
                UUID freshAssignmentId = UUID.fromString("00000000-0000-0000-0000-000000000001");
                Map<String, Object> startBody = Map.of(
                                "mobileWorkAssignmentId", freshAssignmentId.toString(),
                                "technicianId", UUID.fromString("00000000-0000-0000-0000-000000000001").toString(),
                                "segmentType", "DEPART_SHOP");

                // Step 1: start segment and extract travelSegmentId
                String travelSegmentId = givenWithGatewayAuth()
                                .body(startBody)
                                .when().post("/v1/workorders/travelSegments/start")
                                .then()
                                .statusCode(201)
                                .extract().path("travelSegmentId");

                // Step 2: attempt adjustment on IN_PROGRESS segment → 409
                givenWithGatewayAuth()
                                .body(Map.of("adjustmentReason", "test reason"))
                                .when().post("/v1/workorders/travelSegments/" + travelSegmentId + "/adjustments")
                                .then()
                                .statusCode(409)
                                .body("code", equalTo("TRAVEL_SEGMENT_CONFLICT"));
        }

        // ── TSC-005: Create adjustment → 404 non-existent id (GREEN phase) ───────────

        /**
         * TSC-005 — POST /{id}/adjustments with a non-existent travelSegmentId
         * returns HTTP 404 because the service raises a not-found domain exception.
         */
        @Test
        @DisplayName("TSC-005: POST /{id}/adjustments with non-existent id returns 404")
        void TSC005_createAdjustment_notFound_returns404() {
                // Issue CAP-139 Story #67: AC7/AC8 — adjustment raises not-found for unknown id
                UUID randomSegmentId = UUID.fromString("00000000-0000-0000-0000-000000000001");

                givenWithGatewayAuth()
                                .contentType(ContentType.JSON)
                                .body(Map.of("adjustmentReason", "test reason"))
                                .when()
                                .post("/v1/workorders/travelSegments/{travelSegmentId}/adjustments",
                                                randomSegmentId)
                                .then()
                                .log().ifValidationFails()
                                .statusCode(404);
        }

        // ── TSC-008: Submit travel segments happy path → 200 with SUBMITTED status ───

        /**
         * TSC-008 — POST /submit/{mobileWorkAssignmentId} with existing COMPLETED
         * segments
         * returns HTTP 200 with a list containing at least one segment in SUBMITTED
         * status.
         * Seeds a segment directly via repository so the technicianId matches the
         * authenticated user resolved by the service from the security context.
         */
        @Test
        @DisplayName("TSC-008: POST /submit/{id} with COMPLETED segments returns 200 with SUBMITTED status")
        void TSC008_submitTravelSegments_happyPath_returns200WithSubmittedStatus() {
                // Issue CAP-139 Story #67: AC6 — submit sets SUBMITTED status on eligible
                // segments
                // The service resolves technicianId from the security context username.
                // GatewayAuthoritiesFilter sets the principal from the X-User header
                // (SYSTEM_USER_ID).
                UUID freshAssignmentId = UUID.fromString("00000000-0000-0000-0000-000000000001");
                UUID technicianId = UUID.fromString(SYSTEM_USER_ID);

                // Seed a COMPLETED segment directly so submit can find it
                TravelSegment segment = TravelSegment.builder()
                                .mobileWorkAssignmentId(freshAssignmentId)
                                .technicianId(technicianId)
                                .segmentType(TravelSegmentType.DEPART_SHOP)
                                .startAt(Instant.now(TEST_CLOCK).minusSeconds(300))
                                .status(TravelSegmentStatus.COMPLETED)
                                .createdBy(SYSTEM_USER_ID)
                                .build();
                travelSegmentRepository.save(segment);

                // Submit segments and assert the list contains at least one SUBMITTED entry
                givenWithGatewayAuth()
                                .contentType(ContentType.JSON)
                                .body(Map.of("workDate", "2025-01-15"))
                                .when()
                                .post("/v1/workorders/travelSegments/submit/{mobileWorkAssignmentId}",
                                                freshAssignmentId)
                                .then()
                                .log().ifValidationFails()
                                .statusCode(200)
                                .body("size()", greaterThanOrEqualTo(1))
                                .body("[0].status", equalTo("SUBMITTED"));
        }
}
