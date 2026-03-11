package com.positivity.workorder.contract;

import java.time.ZoneOffset;
import java.time.Clock;

import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import com.positivity.workorder.internal.entity.Workorder;
import com.positivity.workorder.internal.entity.WorkSession;
import com.positivity.workorder.internal.enums.WorkSessionStatus;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import com.positivity.workorder.internal.repository.BreakSegmentRepository;
import com.positivity.workorder.internal.repository.WorkSessionRepository;
import com.positivity.workorder.internal.repository.WorkorderRepository;
import com.positivity.workorder.support.BaseContractIntegrationTest;

import io.restassured.http.ContentType;

/**
 * Contract behavior integration tests for WorkSession Start/Stop (CAP-139 Story
 * #68).
 *
 * <p>
 * Verifies REST contract for work session lifecycle — start, stop, break
 * management —
 * including overlap prevention, lock guards, and structured error responses.
 * </p>
 *
 * <p>
 * Endpoint contract under test:
 * </p>
 * <ul>
 * <li>POST /v1/workorders/workSessions/start → 201</li>
 * <li>POST /v1/workorders/workSessions/{workSessionId}/stop → 200</li>
 * <li>POST /v1/workorders/workSessions/{workSessionId}/breaks → 201</li>
 * <li>POST
 * /v1/workorders/workSessions/{workSessionId}/breaks/{breakSegmentId}/stop →
 * 200</li>
 * </ul>
 *
 * Issue: CAP-139 Story #68
 */
@DisplayName("WorkSession Start/Stop Contract Behavior Tests (CAP-139 Story #68)")
@Import(ContractTestConfiguration.class)
class WorkSessionContractBehaviorIT extends BaseContractIntegrationTest {
        private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);

        @Autowired
        private WorkSessionRepository workSessionRepository;

        // @Autowired
        // private BreakSegmentRepository breakSegmentRepository;

        @Autowired
        private WorkorderRepository workorderRepository;

        @AfterEach
        void tearDown() {
                purgeTestData();
        }

        // ── Fixtures ─────────────────────────────────────────────────────────────

        private static final UUID MECHANIC_ID = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
        private static final UUID TASK_ID = UUID.fromString("cccccccc-0000-0000-0000-000000000003");
        private static final UUID LOCATION_ID = UUID.fromString("dddddddd-0000-0000-0000-000000000004");

        private Map<String, Object> validStartBody(UUID workOrderId) {
                return Map.of(
                                "mechanicId", MECHANIC_ID.toString(),
                                "workOrderId", workOrderId.toString(),
                                "workOrderTaskId", TASK_ID.toString(),
                                "locationId", LOCATION_ID.toString());
        }

        private UUID seedWorkorder() {
                Workorder workorder = Workorder.builder().build();
                return workorderRepository.save(workorder).getId();
        }

        // ── WSC-001: Start session happy path ─────────────────────────────────────

        @Test
        @DisplayName("WSC-001: POST /start returns 201 with IN_PROGRESS status, non-null startAt and workSessionId")
        void startWorkSession_happyPath_returns201WithInProgressStatus() {
                // Issue CAP-139 Story #68: AC1 — happy path start
                UUID workorderId = seedWorkorder();
                givenWithGatewayAuth()
                                .contentType(ContentType.JSON)
                                .body(validStartBody(workorderId))
                                .when()
                                .post("/v1/workorders/workSessions/start")
                                .then()
                                .log().ifValidationFails()
                                .statusCode(201)
                                .body("status", equalTo("IN_PROGRESS"))
                                .body("startAt", notNullValue())
                                .body("workSessionId", notNullValue());
        }

        // ── WSC-002: Missing required field returns 400 ───────────────────────────

        @Test
        @DisplayName("WSC-002: POST /start with missing workOrderId returns 400")
        void startWorkSession_missingRequiredField_returns400() {
                // Issue CAP-139 Story #68: validation — missing workOrderId
                Map<String, Object> incompleteBody = Map.of(
                                "mechanicId", MECHANIC_ID.toString(),
                                "workOrderTaskId", TASK_ID.toString(),
                                "locationId", LOCATION_ID.toString()
                // workOrderId intentionally absent
                );

                givenWithGatewayAuth()
                                .contentType(ContentType.JSON)
                                .body(incompleteBody)
                                .when()
                                .post("/v1/workorders/workSessions/start")
                                .then()
                                .log().ifValidationFails()
                                .statusCode(400)
                                .body("code", equalTo("VALIDATION_FAILED"))
                                .body("fieldErrors", not(empty()))
                                .body("fieldErrors[0].field", notNullValue())
                                .body("status", equalTo(400))
                                .body("correlationId", notNullValue());
        }

        // ── WSC-003: Overlap without override returns 409 ─────────────────────────

        @Test
        @DisplayName("WSC-003: Starting a second session for same mechanic without override returns 409")
        void startWorkSession_overlap_withoutOverride_returns409() {
                // Issue CAP-139 Story #68: AC2 — overlap rejection
                UUID workorderId = seedWorkorder();
                // Seed first session
                givenWithGatewayAuth()
                                .contentType(ContentType.JSON)
                                .body(validStartBody(workorderId))
                                .when()
                                .post("/v1/workorders/workSessions/start")
                                .then()
                                .statusCode(201);

                // Attempt second session for same mechanic — should conflict
                givenWithGatewayAuth()
                                .contentType(ContentType.JSON)
                                .body(validStartBody(workorderId))
                                .when()
                                .post("/v1/workorders/workSessions/start")
                                .then()
                                .log().ifValidationFails()
                                .statusCode(409);
        }

        // ── WSC-004: Stop session happy path ──────────────────────────────────────

        @Test
        @DisplayName("WSC-004: POST /{workSessionId}/stop returns 200 with COMPLETED status and non-null endAt")
        void stopWorkSession_happyPath_returns200WithCompletedStatus() {
                // Issue CAP-139 Story #68: AC4 — stop session
                UUID workorderId = seedWorkorder();

                // Start a session first to get a workSessionId
                String startedId = givenWithGatewayAuth()
                                .contentType(ContentType.JSON)
                                .body(validStartBody(workorderId))
                                .when()
                                .post("/v1/workorders/workSessions/start")
                                .then()
                                .statusCode(201)
                                .extract()
                                .path("workSessionId");

                givenWithGatewayAuth()
                                .contentType(ContentType.JSON)
                                .body(Map.of())
                                .when()
                                .post("/v1/workorders/workSessions/{workSessionId}/stop", startedId)
                                .then()
                                .log().ifValidationFails()
                                .statusCode(200)
                                .body("status", equalTo("COMPLETED"))
                                .body("endAt", notNullValue());
        }

        // ── WSC-005: Stop non-existent session returns 404 ────────────────────────

        @Test
        @DisplayName("WSC-005: POST /stop with unknown workSessionId returns 404 with error code WORK_SESSION_NOT_FOUND")
        void stopWorkSession_nonExistentSession_returns404() {
                // Issue CAP-139 Story #68: AC5 — 404 for unknown session
                UUID randomId = UUID.fromString("00000000-0000-0000-0000-000000000001");

                givenWithGatewayAuth()
                                .contentType(ContentType.JSON)
                                .body(Map.of())
                                .when()
                                .post("/v1/workorders/workSessions/{workSessionId}/stop", randomId)
                                .then()
                                .log().ifValidationFails()
                                .statusCode(404)
                                // Application must return a structured error with specific code,
                                // not Spring's generic "no-route" 404 response.
                                .body("code", equalTo("WORK_SESSION_NOT_FOUND"));
        }

        // ── WSC-006: Add break segment happy path ─────────────────────────────────

        @Test
        @DisplayName("WSC-006: POST /{workSessionId}/breaks returns 201 with non-null breakStartAt")
        void addBreakSegment_happyPath_returns201() {
                // Issue CAP-139 Story #68: AC6 — add break
                UUID workorderId = seedWorkorder();

                // Start a session first
                String workSessionId = givenWithGatewayAuth()
                                .contentType(ContentType.JSON)
                                .body(validStartBody(workorderId))
                                .when()
                                .post("/v1/workorders/workSessions/start")
                                .then()
                                .statusCode(201)
                                .extract()
                                .path("workSessionId");

                givenWithGatewayAuth()
                                .contentType(ContentType.JSON)
                                .body(Map.of("breakType", "MEAL"))
                                .when()
                                .post("/v1/workorders/workSessions/{workSessionId}/breaks", workSessionId)
                                .then()
                                .log().ifValidationFails()
                                .statusCode(201)
                                .body("breakStartAt", notNullValue());
        }

        // ── WSC-007: Add break to locked/approved session returns 409 ─────────────

        @Test
        @DisplayName("WSC-007: POST breaks on APPROVED (locked) session returns 409")
        void addBreakSegment_onLockedSession_returns409() {
                // Issue CAP-139 Story #68: break on locked session rejected
                // Seed an APPROVED (locked) session directly via repository.
                Workorder workorder = workorderRepository.save(Workorder.builder().build());
                WorkSession lockedSession = WorkSession.builder()
                                .mechanicId(MECHANIC_ID)
                                .workOrder(workorder)
                                .workOrderTaskId(TASK_ID)
                                .locationId(LOCATION_ID)
                                .startAt(Instant.now(TEST_CLOCK).minus(2, ChronoUnit.HOURS))
                                .endAt(Instant.now(TEST_CLOCK).minus(30, ChronoUnit.MINUTES))
                                .status(WorkSessionStatus.APPROVED)
                                .locked(true)
                                .totalDurationSeconds(5400)
                                .overlapOverrideUsed(false)
                                .build();
                lockedSession = workSessionRepository.save(lockedSession);

                givenWithGatewayAuth()
                                .contentType(ContentType.JSON)
                                .body(Map.of("breakType", "REST"))
                                .when()
                                .post("/v1/workorders/workSessions/{workSessionId}/breaks",
                                                lockedSession.getWorkSessionId())
                                .then()
                                .log().ifValidationFails()
                                .statusCode(409);
        }

        // ── WSC-008: Stop break segment happy path ────────────────────────────────

        @Test
        @DisplayName("WSC-008: POST /{workSessionId}/breaks/{breakSegmentId}/stop returns 200 with non-null breakEndAt")
        void WSC008_stopBreakSegment_happyPath_returns200() {
                // Issue CAP-139 Story #68: AC7 — stop break segment happy path
                UUID workorderId = seedWorkorder();

                // Step 1: Start a work session to get a workSessionId
                String workSessionId = givenWithGatewayAuth()
                                .contentType(ContentType.JSON)
                                .body(validStartBody(workorderId))
                                .when()
                                .post("/v1/workorders/workSessions/start")
                                .then()
                                .statusCode(201)
                                .extract()
                                .path("workSessionId");

                // Step 2: Start a break to get a breakSegmentId
                String breakSegmentId = givenWithGatewayAuth()
                                .contentType(ContentType.JSON)
                                .body(Map.of("breakType", "MEAL"))
                                .when()
                                .post("/v1/workorders/workSessions/{workSessionId}/breaks", workSessionId)
                                .then()
                                .statusCode(201)
                                .extract()
                                .path("breakSegmentId");

                // Step 3: Stop the break — assert 200 and non-null breakEndAt
                givenWithGatewayAuth()
                                .when()
                                .post("/v1/workorders/workSessions/{workSessionId}/breaks/{breakSegmentId}/stop",
                                                workSessionId, breakSegmentId)
                                .then()
                                .log().ifValidationFails()
                                .statusCode(200)
                                .body("breakEndAt", notNullValue());
        }
}
