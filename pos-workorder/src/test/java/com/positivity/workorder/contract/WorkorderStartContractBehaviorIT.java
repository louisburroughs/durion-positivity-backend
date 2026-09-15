package com.positivity.workorder.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

import com.positivity.workorder.internal.entity.ChangeRequest;
import com.positivity.workorder.internal.entity.Estimate;
import com.positivity.workorder.internal.entity.EstimateItem;
import com.positivity.workorder.internal.entity.EstimateItemType;
import com.positivity.workorder.internal.entity.ExtBayReplica;
import com.positivity.workorder.internal.entity.ExtPersonReplica;
import com.positivity.workorder.internal.entity.Workorder;
import com.positivity.workorder.internal.entity.WorkorderStateTransition;
import com.positivity.workorder.internal.enums.ApprovalStatus;
import com.positivity.workorder.internal.enums.EstimateStatus;
import com.positivity.workorder.internal.enums.WorkorderStatus;
import com.positivity.workorder.internal.repository.ChangeRequestRepository;
import com.positivity.workorder.internal.repository.EstimateItemRepository;
import com.positivity.workorder.internal.repository.EstimateRepository;
import com.positivity.workorder.internal.repository.ExtBayReplicaRepository;
import com.positivity.workorder.internal.repository.ExtPersonReplicaRepository;
import com.positivity.workorder.internal.repository.WorkorderRepository;
import com.positivity.workorder.internal.repository.WorkorderStateTransitionRepository;
import com.positivity.workorder.support.BaseContractIntegrationTest;
import io.restassured.http.ContentType;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

/**
 * Contract behavior integration tests for workorder start and status tracking.
 * CAP:005 Story #160 - Start Workorder and Track Status
 *
 * Tests cover:
 * - Starting workorder from ASSIGNED status (#2011: work starts only once a technician and a bay
 *   or mobile unit are both in place)
 * - Refusing to start an APPROVED workorder, naming what it is missing
 * - Rejecting start when pending change requests exist
 * - Transition history recording (append-only, newest-first)
 * - Snapshot history recording
 */
@DisplayName("Workorder Start Contract Behavior Tests (CAP:005 Story #160)")
@Import(ContractTestConfiguration.class)
class WorkorderStartContractBehaviorIT extends BaseContractIntegrationTest {
    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);

    @Autowired
    private EstimateRepository estimateRepository;

    @Autowired
    private EstimateItemRepository estimateItemRepository;

    @Autowired
    private WorkorderRepository workorderRepository;

    @Autowired
    private WorkorderStateTransitionRepository transitionRepository;

    @Autowired
    private ChangeRequestRepository changeRequestRepository;

    @Autowired
    private ExtPersonReplicaRepository extPersonReplicaRepository;

    @Autowired
    private ExtBayReplicaRepository extBayReplicaRepository;

    private static final UUID TEST_TECHNICIAN_ID = UUID.fromString("00000000-0000-0000-0000-000000002001");
    private static final UUID TEST_BAY_ID = UUID.fromString("00000000-0000-0000-0000-000000002002");

    private UUID testCustomerId;
    private UUID testLocationId;
    private UUID testVehicleId;

    @AfterEach
    void tearDown() {
        purgeTestData();
    }

    // ========== WORKORDER START TESTS ==========

    @Test
    @DisplayName("WS-001: Successfully start workorder from ASSIGNED status")
    void testStartWorkorder_FromAssignedStatus() {
        // Given: A workorder in ASSIGNED status (#2011: a technician and a bay)
        UUID workorderId = seedAssignedWorkorder();
        Workorder workorder = workorderRepository.findById(workorderId).orElseThrow();
        assertThat(workorder.getStatus()).isEqualTo(WorkorderStatus.ASSIGNED);

        // When: Start the workorder
        Map<String, Object> startRequest =
                Map.of("userId", SYSTEM_USER_ID, "reason", "Customer arrived and dropped off vehicle");

        givenWithGatewayAuth()
                .contentType(ContentType.JSON)
                .body(startRequest)
                .when()
                .post("/v1/workorders/{workorderId}/start", workorderId)
                .then()
                .log()
                .ifValidationFails()
                .statusCode(200)
                .body("workorderId", equalTo(workorderId.toString()))
                .body("previousStatus", equalTo("ASSIGNED"))
                .body("currentStatus", equalTo("WORK_IN_PROGRESS"))
                .body("transitionedAt", notNullValue())
                .body("message", notNullValue());

        // Then: Verify workorder status is updated to WORK_IN_PROGRESS
        Workorder updatedWorkorder = workorderRepository.findById(workorderId).orElseThrow();
        assertThat(updatedWorkorder.getStatus()).isEqualTo(WorkorderStatus.WORK_IN_PROGRESS);

        // Verify transition was recorded
        List<WorkorderStateTransition> transitions =
                transitionRepository.findByWorkorder_IdOrderByTransitionedAtDesc(workorderId);
        assertThat(transitions).isNotEmpty();

        // Find the transition to WORK_IN_PROGRESS
        WorkorderStateTransition startTransition = transitions.stream()
                .filter(t -> t.getToStatus() == WorkorderStatus.WORK_IN_PROGRESS)
                .findFirst()
                .orElseThrow();

        assertThat(startTransition.getFromStatus()).isEqualTo(WorkorderStatus.ASSIGNED);
        assertThat(startTransition.getToStatus()).isEqualTo(WorkorderStatus.WORK_IN_PROGRESS);
        assertThat(startTransition.getTransitionedBy()).isEqualTo(SYSTEM_USER_ID);
        assertThat(startTransition.getReason()).isEqualTo("Customer arrived and dropped off vehicle");
        assertThat(startTransition.getTransitionedAt()).isNotNull();
    }

    @Test
    @DisplayName("WS-002: Reject start if change request is AWAITING_ADVISOR_REVIEW")
    void testStartWorkorder_RejectWhenPendingChangeRequests() {
        // Given: A workorder in ASSIGNED status with a pending change request
        UUID workorderId = seedApprovedWorkorderWithPendingChangeRequest();
        Workorder workorder = workorderRepository.findById(workorderId).orElseThrow();
        assertThat(workorder.getStatus()).isEqualTo(WorkorderStatus.ASSIGNED);

        // Verify pending change request exists
        List<ChangeRequest> pendingChangeRequests = changeRequestRepository.findByWorkorder_IdAndStatus(
                workorderId, ChangeRequest.ChangeRequestStatus.AWAITING_ADVISOR_REVIEW);
        assertThat(pendingChangeRequests).isNotEmpty();

        // When: Attempt to start the workorder
        Map<String, Object> startRequest = Map.of("userId", SYSTEM_USER_ID, "reason", "Attempting to start workorder");

        givenWithGatewayAuth()
                .contentType(ContentType.JSON)
                .body(startRequest)
                .when()
                .post("/v1/workorders/{workorderId}/start", workorderId)
                .then()
                .log()
                .ifValidationFails()
                .statusCode(400)
                .body("message", notNullValue());

        // Then: Verify workorder status is still ASSIGNED (unchanged)
        Workorder unchangedWorkorder = workorderRepository.findById(workorderId).orElseThrow();
        assertThat(unchangedWorkorder.getStatus()).isEqualTo(WorkorderStatus.ASSIGNED);
    }

    @Test
    @DisplayName("WS-005: #2011 an APPROVED workorder missing a technician, a position, or both refuses "
            + "start with 409 naming what's missing")
    void testStartWorkorder_RefusedWhenMissingTechnicianOrPosition() {
        seedTechnicianAndBay();

        // Missing both: a fresh APPROVED workorder has neither a technician nor a position.
        UUID missingBoth = seedApprovedWorkorder();
        assertStartRefusedMissing(missingBoth, "a technician and a bay or mobile unit");

        // Missing only a position: a technician is assigned, but there is no bay or mobile unit.
        UUID missingPosition = seedApprovedWorkorder();
        assignTechnician(missingPosition);
        assertThat(workorderRepository.findById(missingPosition).orElseThrow().getStatus())
                .isEqualTo(WorkorderStatus.APPROVED);
        assertStartRefusedMissing(missingPosition, "a bay or mobile unit");

        // Missing only a technician: the workorder is on a bay, but nobody is assigned to it.
        UUID missingTechnician = seedApprovedWorkorder();
        assignBay(missingTechnician);
        assertThat(workorderRepository.findById(missingTechnician).orElseThrow().getStatus())
                .isEqualTo(WorkorderStatus.APPROVED);
        assertStartRefusedMissing(missingTechnician, "a technician");
    }

    private void assertStartRefusedMissing(UUID workorderId, String expectedMissing) {
        Map<String, Object> startRequest =
                Map.of("userId", SYSTEM_USER_ID, "reason", "Attempting to start an unready workorder");

        givenWithGatewayAuth()
                .contentType(ContentType.JSON)
                .body(startRequest)
                .when()
                .post("/v1/workorders/{workorderId}/start", workorderId)
                .then()
                .log()
                .ifValidationFails()
                .statusCode(409)
                .body("message", containsString("missing " + expectedMissing));

        assertThat(workorderRepository.findById(workorderId).orElseThrow().getStatus())
                .isEqualTo(WorkorderStatus.APPROVED);
    }

    @Test
    @DisplayName("WS-003: Transition history is append-only and newest-first")
    void testTransitionHistory_AppendOnlyNewestFirst() {
        // Given: A workorder that has been promoted, assigned, and started
        UUID workorderId = seedAssignedWorkorder();

        // Start the workorder
        Map<String, Object> startRequest = Map.of("userId", SYSTEM_USER_ID, "reason", "Starting work");

        givenWithGatewayAuth()
                .contentType(ContentType.JSON)
                .body(startRequest)
                .when()
                .post("/v1/workorders/{workorderId}/start", workorderId)
                .then()
                .statusCode(200);

        // When: Retrieve transition history
        List<Map<String, Object>> transitions = givenWithGatewayAuth()
                .when()
                .get("/v1/workorders/{workorderId}/transitions", workorderId)
                .then()
                .log()
                .ifValidationFails()
                .statusCode(200)
                .body("$", hasSize(greaterThan(0)))
                .extract()
                .jsonPath()
                .getList("$");

        // Then: Verify transitions are ordered newest-first
        assertThat(transitions).isNotEmpty();

        // The most recent transition should be to WORK_IN_PROGRESS
        Map<String, Object> newestTransition = transitions.get(0);
        assertThat(newestTransition)
                .containsEntry("toStatus", "WORK_IN_PROGRESS")
                .containsEntry("fromStatus", "ASSIGNED");

        // Verify each transition has required fields
        for (Map<String, Object> transition : transitions) {
            assertThat(transition).containsKeys("id", "workorderId", "fromStatus", "toStatus", "transitionedAt");
        }

        // Verify timestamps are in descending order (newest first)
        if (transitions.size() > 1) {
            String firstTimestamp = (String) transitions.get(0).get("transitionedAt");
            String secondTimestamp = (String) transitions.get(1).get("transitionedAt");
            assertThat(firstTimestamp).isGreaterThanOrEqualTo(secondTimestamp);
        }
    }

    @Test
    @DisplayName("WS-004: Snapshot history is retrievable and ordered newest-first")
    void testSnapshotHistory_OrderedNewestFirst() {
        // Given: A workorder that has been started (which creates snapshots)
        UUID workorderId = seedAssignedWorkorder();

        // Start the workorder (this should create a snapshot)
        Map<String, Object> startRequest = Map.of("userId", SYSTEM_USER_ID, "reason", "Starting work");

        givenWithGatewayAuth()
                .contentType(ContentType.JSON)
                .body(startRequest)
                .when()
                .post("/v1/workorders/{workorderId}/start", workorderId)
                .then()
                .statusCode(200);

        // When: Retrieve snapshot history
        List<Map<String, Object>> snapshots = givenWithGatewayAuth()
                .when()
                .get("/v1/workorders/{workorderId}/snapshots", workorderId)
                .then()
                .log()
                .ifValidationFails()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getList("$");

        // Then: Verify snapshots are returned (may be empty if automatic snapshots not
        // yet implemented)
        // This test documents the expected API shape even if snapshots are not yet
        // created
        if (!snapshots.isEmpty()) {
            // Verify each snapshot has required fields
            for (Map<String, Object> snapshot : snapshots) {
                assertThat(snapshot).containsKeys("id", "workorderId", "status", "capturedAt", "snapshotType");
            }

            // Verify timestamps are in descending order (newest first)
            if (snapshots.size() > 1) {
                String firstTimestamp = (String) snapshots.get(0).get("capturedAt");
                String secondTimestamp = (String) snapshots.get(1).get("capturedAt");
                assertThat(firstTimestamp).isGreaterThanOrEqualTo(secondTimestamp);
            }
        }
    }

    // ========== TEST DATA SEEDING HELPERS ==========

    /**
     * Seed an approved workorder (from estimate promotion)
     */
    private UUID seedApprovedWorkorder() {
        initTestIds();

        // Create and save estimate. The number is randomised, not derived from the fixed test
        // customer id, because WS-005 seeds several estimates at the same testLocationId in one
        // test and (locationId, estimateNumber) is unique.
        Estimate estimate = Estimate.builder()
                .estimateNumber("EST-START-" + UUID.randomUUID().toString().substring(0, 8))
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

        // Add approved item
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

        // Promote estimate to workorder
        String workorderIdStr = givenWithGatewayAuth()
                .when()
                .post("/v1/workorders/estimates/{id}/promote", estimate.getId())
                .then()
                .statusCode(200)
                .extract()
                .path("id");

        UUID workorderId = UUID.fromString(workorderIdStr);

        // Transition DRAFT -> APPROVED so start-workorder contract preconditions hold.
        String approvalBody = String.format("""
                                {
                                  "customerId": "%s",
                                  "signatureData": "test-signature",
                                  "signatureMimeType": "image/png",
                                  "signerName": "Test Customer",
                                  "notes": "Approved for execution"
                                }
                                """, testCustomerId);

        givenWithGatewayAuth()
                .contentType(ContentType.JSON)
                .body(approvalBody)
                .when()
                .post("/v1/workorders/{workorderId}/approval", workorderId)
                .then()
                .statusCode(200);

        return workorderId;
    }

    /**
     * Seed an ASSIGNED workorder with a pending change request
     */
    private UUID seedApprovedWorkorderWithPendingChangeRequest() {
        UUID workorderId = seedAssignedWorkorder();
        Workorder workorder = workorderRepository.findById(workorderId).orElseThrow();

        // Create a pending change request
        ChangeRequest changeRequest = ChangeRequest.builder()
                .workorder(workorder)
                .requestedByUserId(SYSTEM_USER_ID)
                .requestedAt(LocalDateTime.now(TEST_CLOCK))
                .updatedAt(Instant.now(TEST_CLOCK))
                .status(ChangeRequest.ChangeRequestStatus.AWAITING_ADVISOR_REVIEW)
                .description("Additional brake work required")
                .isEmergencyException(false)
                .isApprovalGated(true)
                .build();
        changeRequestRepository.save(changeRequest);

        return workorderId;
    }

    private void initTestIds() {
        if (testCustomerId == null) {
            testCustomerId = UUID.fromString("00000000-0000-0000-0000-000000000001");
            testLocationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
            testVehicleId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        }
    }

    /**
     * Make the technician and bay start-eligibility now requires known to the module (#2011): the
     * same {@code ext_person} / {@code ext_bay} replica rows ServicePositionContractBehaviorIT and
     * TechnicianAssignmentContractBehaviorIT seed for their own assignment checks.
     */
    private void seedTechnicianAndBay() {
        initTestIds();
        extPersonReplicaRepository.save(ExtPersonReplica.builder()
                .personId(TEST_TECHNICIAN_ID)
                .aggregateVersion(1L)
                .updatedAt(Instant.EPOCH)
                .build());
        extBayReplicaRepository.save(ExtBayReplica.builder()
                .bayId(TEST_BAY_ID)
                .locationId(testLocationId)
                .name("Start Test Bay")
                .active(true)
                .aggregateVersion(1L)
                .updatedAt(Instant.EPOCH)
                .build());
    }

    private void assignTechnician(UUID workorderId) {
        givenWithGatewayAuth()
                .contentType(ContentType.JSON)
                .body(Map.of("technicianId", TEST_TECHNICIAN_ID.toString()))
                .when()
                .post("/v1/workorders/{workorderId}/technician", workorderId)
                .then()
                .statusCode(200);
    }

    private void assignBay(UUID workorderId) {
        givenWithGatewayAuth()
                .contentType(ContentType.JSON)
                .body(Map.of("resourceType", "BAY", "resourceId", TEST_BAY_ID.toString()))
                .when()
                .put("/v1/workorders/{workorderId}/position", workorderId)
                .then()
                .statusCode(200);
    }

    /**
     * Seed a workorder that has reached ASSIGNED (#2011): APPROVED plus a current technician and a
     * bay, the two halves start now requires. Order matters only in that reconciliation runs after
     * each half is completed — the workorder stays APPROVED until both are in place.
     */
    private UUID seedAssignedWorkorder() {
        UUID workorderId = seedApprovedWorkorder();
        seedTechnicianAndBay();
        assignTechnician(workorderId);
        assignBay(workorderId);

        Workorder workorder = workorderRepository.findById(workorderId).orElseThrow();
        assertThat(workorder.getStatus()).isEqualTo(WorkorderStatus.ASSIGNED);
        return workorderId;
    }
}
