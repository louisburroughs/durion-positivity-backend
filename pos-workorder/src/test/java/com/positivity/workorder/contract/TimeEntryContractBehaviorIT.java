package com.positivity.workorder.contract;

import java.time.ZoneOffset;
import java.time.Clock;

import static org.hamcrest.Matchers.equalTo;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import com.positivity.workorder.internal.entity.TimeEntry;
import com.positivity.workorder.internal.enums.TimeEntryStatus;
import com.positivity.workorder.internal.repository.TimeEntryRepository;
import com.positivity.workorder.support.BaseContractIntegrationTest;

import io.restassured.http.ContentType;

/**
 * Contract behavior integration tests for Approve Submitted Time
 * (CAP-139 Story #66).
 *
 * <p>
 * Verifies REST contract wiring for time entry approval and rejection
 * endpoints.
 * GREEN phase: Unknown UUID requests return 404. Bean Validation (400) tests
 * unchanged. TEC-005 seeds a SUBMITTED entry and asserts 200 with APPROVED
 * status.
 * </p>
 *
 * <p>
 * Endpoints under test:
 * </p>
 * <ul>
 * <li>POST /v1/workorders/timeEntries/{id}/approve → 404 (unknown id) or 200
 * (seeded)</li>
 * <li>POST /v1/workorders/timeEntries/{id}/reject → 404 (unknown id) or 400
 * (validation)</li>
 * </ul>
 *
 * <p>
 * Required authorities:
 * </p>
 * <ul>
 * <li>{@code TimeEntry:Approve} — guard on approve endpoint</li>
 * <li>{@code TimeEntry:Reject} — guard on reject endpoint</li>
 * </ul>
 *
 * Issue: CAP-139 Story #66
 */
@DisplayName("TimeEntry Approve/Reject Contract Behavior Tests — GREEN phase (CAP-139 Story #66)")
@Import(ContractTestConfiguration.class)
class TimeEntryContractBehaviorIT extends BaseContractIntegrationTest {
    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);


    @Autowired
    private TimeEntryRepository timeEntryRepository;

    @AfterEach
    void cleanTimeEntries() {
        timeEntryRepository.deleteAll();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Generates a fresh random UUID path segment to avoid cross-test collisions.
     */
    private static String randomEntryPath(String suffix) {
        return "/v1/workorders/timeEntries/" + UUID.randomUUID() + suffix;
    }

    private Map<String, String> validRejectBody() {
        return Map.of("rejectionReason", "Hours do not match timesheet record");
    }

    // ── TEC-001: Approve with unknown id → 404 ────────────────────────────────

    /**
     * TEC-001 — POST approve with an unknown {@code timeEntryId} returns HTTP 404.
     * GREEN phase:
     * {@link com.positivity.workorder.internal.service.TimeEntryServiceImpl}
     * throws {@code TimeEntryNotFoundException} when the entity is not found.
     */
    @Test
    @DisplayName("TEC-001 GREEN: POST /approve with unknown timeEntryId returns 404")
    void TEC001_approveTimeEntry_unknownId_returns404() {
        // Issue CAP-139 Story #66: AC1 — approve returns 404 for non-existent entry
        givenWithGatewayAuth()
                .contentType(ContentType.JSON)
                .when()
                .post(randomEntryPath("/approve"))
                .then()
                .log().ifValidationFails()
                .statusCode(404);
    }

    // ── TEC-002: Reject with unknown id → 404 ─────────────────────────────────

    /**
     * TEC-002 — POST reject with a valid body but unknown {@code timeEntryId}
     * returns HTTP 404. GREEN phase service throws
     * {@code TimeEntryNotFoundException}.
     */
    @Test
    @DisplayName("TEC-002 GREEN: POST /reject with unknown timeEntryId returns 404")
    void TEC002_rejectTimeEntry_unknownId_returns404() {
        // Issue CAP-139 Story #66: AC2 — reject returns 404 for non-existent entry
        givenWithGatewayAuth()
                .contentType(ContentType.JSON)
                .body(validRejectBody())
                .when()
                .post(randomEntryPath("/reject"))
                .then()
                .log().ifValidationFails()
                .statusCode(404);
    }

    // ── TEC-003: Reject with missing body → 400 ───────────────────────────────

    /**
     * TEC-003 — POST reject with an empty JSON object (missing required
     * {@code rejectionReason} field) returns HTTP 400. Bean Validation enforces
     * {@code @NotBlank} before the service is ever invoked.
     */
    @Test
    @DisplayName("TEC-003 GREEN: POST /reject with empty body returns 400 (missing rejectionReason)")
    void TEC003_rejectTimeEntry_emptyBody_returns400() {
        // Issue CAP-139 Story #66: AC5 — @NotBlank on rejectionReason enforced at
        // controller
        givenWithGatewayAuth()
                .contentType(ContentType.JSON)
                .body(Map.of())
                .when()
                .post(randomEntryPath("/reject"))
                .then()
                .log().ifValidationFails()
                .statusCode(400);
    }

    // ── TEC-004: Reject with blank reason → 400 ───────────────────────────────

    /**
     * TEC-004 — POST reject with an explicit empty string for
     * {@code rejectionReason}
     * returns HTTP 400. {@code @NotBlank} treats blank strings as invalid.
     */
    @Test
    @DisplayName("TEC-004 GREEN: POST /reject with blank rejectionReason returns 400")
    void TEC004_rejectTimeEntry_blankReason_returns400() {
        // Issue CAP-139 Story #66: AC5 — blank string fails @NotBlank validation
        givenWithGatewayAuth()
                .contentType(ContentType.JSON)
                .body(Map.of("rejectionReason", ""))
                .when()
                .post(randomEntryPath("/reject"))
                .then()
                .log().ifValidationFails()
                .statusCode(400);
    }

    // ── TEC-005: Approve seeded SUBMITTED entry → 200 ─────────────────────────

    /**
     * TEC-005 — POST approve with a seeded SUBMITTED time entry returns HTTP 200
     * with the entry status transitioned to APPROVED.
     */
    @Test
    @DisplayName("TEC-005 GREEN: POST /approve with seeded SUBMITTED entry returns 200 with APPROVED status")
    void TEC005_approveTimeEntry_seededSubmittedEntry_returns200() {
        // Issue CAP-139 Story #66: AC1 — approve transitions SUBMITTED → APPROVED
        TimeEntry entry = timeEntryRepository.save(buildSubmittedEntry());

        givenWithGatewayAuth()
                .contentType(ContentType.JSON)
                .when()
                .post("/v1/workorders/timeEntries/{id}/approve", entry.getTimeEntryId())
                .then()
                .log().ifValidationFails()
                .statusCode(200)
                .body("status", equalTo("APPROVED"));
    }

    // ── TEC-006: Reject seeded SUBMITTED entry → 200 ──────────────────────────

    /**
     * TEC-006 — POST reject with a valid reason and a seeded SUBMITTED time entry
     * returns HTTP 200 with the entry status transitioned to REJECTED.
     */
    @Test
    @DisplayName("TEC-006 GREEN: POST /reject with seeded SUBMITTED entry returns 200 with REJECTED status")
    void TEC006_rejectTimeEntry_seededSubmittedEntry_returns200() {
        // Issue CAP-139 Story #66: AC2 — reject transitions SUBMITTED → REJECTED
        TimeEntry entry = timeEntryRepository.save(buildSubmittedEntry());

        givenWithGatewayAuth()
                .contentType(ContentType.JSON)
                .body(validRejectBody())
                .when()
                .post("/v1/workorders/timeEntries/{id}/reject", entry.getTimeEntryId())
                .then()
                .log().ifValidationFails()
                .statusCode(200)
                .body("status", equalTo("REJECTED"));
    }

    // ── TEC-007: Approve already-APPROVED entry → 409 ─────────────────────────

    /**
     * TEC-007 — POST approve against an entry already in APPROVED state returns
     * HTTP 409 with error code {@code TIME_ENTRY_INVALID_STATE}.
     */
    @Test
    @DisplayName("TEC-007 GREEN: POST /approve with APPROVED entry returns 409 TIME_ENTRY_INVALID_STATE")
    void TEC007_approveTimeEntry_alreadyApproved_returns409() {
        // Issue CAP-139 Story #66: AC3 — wrong-state transitions return 409
        TimeEntry entry = timeEntryRepository.save(buildApprovedEntry());

        givenWithGatewayAuth()
                .contentType(ContentType.JSON)
                .when()
                .post("/v1/workorders/timeEntries/{id}/approve", entry.getTimeEntryId())
                .then()
                .log().ifValidationFails()
                .statusCode(409)
                .body("code", equalTo("TIME_ENTRY_INVALID_STATE"));
    }

    // ── Fixture builders ──────────────────────────────────────────────────────

    private TimeEntry buildSubmittedEntry() {
        return TimeEntry.builder()
                .personId(UUID.randomUUID())
                .workOrderId(UUID.randomUUID())
                .startAt(Instant.now(TEST_CLOCK).minusSeconds(3600))
                .endAt(Instant.now(TEST_CLOCK))
                .status(TimeEntryStatus.SUBMITTED)
                .build();
    }

    private TimeEntry buildApprovedEntry() {
        return TimeEntry.builder()
                .personId(UUID.randomUUID())
                .workOrderId(UUID.randomUUID())
                .startAt(Instant.now(TEST_CLOCK).minusSeconds(3600))
                .endAt(Instant.now(TEST_CLOCK))
                .status(TimeEntryStatus.APPROVED)
                .build();
    }
}
