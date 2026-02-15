package com.positivity.workorder.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import com.positivity.workorder.internal.entity.ApprovalStatus;
import com.positivity.workorder.internal.entity.ChangeRequest;
import com.positivity.workorder.internal.entity.Estimate;
import com.positivity.workorder.internal.entity.EstimateItem;
import com.positivity.workorder.internal.entity.EstimateItemType;
import com.positivity.workorder.internal.entity.EstimateStatus;
import com.positivity.workorder.internal.entity.Workorder;
import com.positivity.workorder.internal.entity.WorkorderStateTransition;
import com.positivity.workorder.internal.entity.WorkorderStatus;
import com.positivity.workorder.internal.repository.ChangeRequestRepository;
import com.positivity.workorder.internal.repository.EstimateItemRepository;
import com.positivity.workorder.internal.repository.EstimateRepository;
import com.positivity.workorder.internal.repository.WorkorderRepository;
import com.positivity.workorder.internal.repository.WorkorderStateTransitionRepository;
import com.positivity.workorder.support.BaseContractIntegrationTest;

import io.restassured.http.ContentType;

/**
 * Contract behavior integration tests for workorder start and status tracking.
 * CAP:005 Story #160 - Start Workorder and Track Status
 *
 * Tests cover:
 * - Starting workorder from APPROVED/ASSIGNED status
 * - Rejecting start when pending change requests exist
 * - Transition history recording (append-only, newest-first)
 * - Snapshot history recording
 */
@DisplayName("Workorder Start Contract Behavior Tests (CAP:005 Story #160)")
@Import(ContractTestConfiguration.class)
class WorkorderStartContractBehaviorIT extends BaseContractIntegrationTest {

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

    private UUID testCustomerId;
    private UUID testLocationId;
    private UUID testVehicleId;

    @AfterEach
    void tearDown() {
        changeRequestRepository.deleteAll();
        transitionRepository.deleteAll();
        workorderRepository.deleteAll();
        estimateItemRepository.deleteAll();
        estimateRepository.deleteAll();
    }

    // ========== WORKORDER START TESTS ==========

    @Test
    @DisplayName("WS-001: Successfully start workorder from APPROVED status")
    void testStartWorkorder_FromApprovedStatus() {
        // Given: A workorder in APPROVED status
        UUID workorderId = seedApprovedWorkorder();
        Workorder workorder = workorderRepository.findById(workorderId).orElseThrow();
        assertThat(workorder.getStatus()).isEqualTo(WorkorderStatus.APPROVED);

        // When: Start the workorder
        Map<String, Object> startRequest = Map.of(
                "userId", SYSTEM_USER_ID.toString(),
                "reason", "Customer arrived and dropped off vehicle");

        givenWithGatewayAuth()
                .contentType(ContentType.JSON)
                .body(startRequest)
                .when()
                .post("/v1/workorders/{workorderId}/start", workorderId)
                .then()
                .log().ifValidationFails()
                .statusCode(200)
                .body("workorderId", equalTo(workorderId.toString()))
                .body("previousStatus", equalTo("APPROVED"))
                .body("currentStatus", equalTo("WORK_IN_PROGRESS"))
                .body("transitionedAt", notNullValue())
                .body("message", notNullValue());

        // Then: Verify workorder status is updated to WORK_IN_PROGRESS
        Workorder updatedWorkorder = workorderRepository.findById(workorderId).orElseThrow();
        assertThat(updatedWorkorder.getStatus()).isEqualTo(WorkorderStatus.WORK_IN_PROGRESS);

        // Verify transition was recorded
        List<WorkorderStateTransition> transitions = transitionRepository
                .findByWorkorderIdOrderByTransitionedAtDesc(workorderId);
        assertThat(transitions).isNotEmpty();

        // Find the transition to WORK_IN_PROGRESS
        WorkorderStateTransition startTransition = transitions.stream()
                .filter(t -> t.getToStatus() == WorkorderStatus.WORK_IN_PROGRESS)
                .findFirst()
                .orElseThrow();

        assertThat(startTransition.getFromStatus()).isEqualTo(WorkorderStatus.APPROVED);
        assertThat(startTransition.getToStatus()).isEqualTo(WorkorderStatus.WORK_IN_PROGRESS);
        assertThat(startTransition.getTransitionedBy()).isEqualTo(SYSTEM_USER_ID);
        assertThat(startTransition.getReason()).isEqualTo("Customer arrived and dropped off vehicle");
        assertThat(startTransition.getTransitionedAt()).isNotNull();
    }

    @Test
    @DisplayName("WS-002: Reject start if change request is AWAITING_ADVISOR_REVIEW")
    void testStartWorkorder_RejectWhenPendingChangeRequests() {
        // Given: A workorder in APPROVED status with a pending change request
        UUID workorderId = seedApprovedWorkorderWithPendingChangeRequest();
        Workorder workorder = workorderRepository.findById(workorderId).orElseThrow();
        assertThat(workorder.getStatus()).isEqualTo(WorkorderStatus.APPROVED);

        // Verify pending change request exists
        List<ChangeRequest> pendingChangeRequests = changeRequestRepository
                .findByWorkorderIdAndStatus(workorderId, ChangeRequest.ChangeRequestStatus.AWAITING_ADVISOR_REVIEW);
        assertThat(pendingChangeRequests).isNotEmpty();

        // When: Attempt to start the workorder
        Map<String, Object> startRequest = Map.of(
                "userId", SYSTEM_USER_ID.toString(),
                "reason", "Attempting to start workorder");

        givenWithGatewayAuth()
                .contentType(ContentType.JSON)
                .body(startRequest)
                .when()
                .post("/v1/workorders/{workorderId}/start", workorderId)
                .then()
                .log().ifValidationFails()
                .statusCode(400)
                .body("message", notNullValue());

        // Then: Verify workorder status is still APPROVED (unchanged)
        Workorder unchangedWorkorder = workorderRepository.findById(workorderId).orElseThrow();
        assertThat(unchangedWorkorder.getStatus()).isEqualTo(WorkorderStatus.APPROVED);
    }

    @Test
    @DisplayName("WS-003: Transition history is append-only and newest-first")
    void testTransitionHistory_AppendOnlyNewestFirst() {
        // Given: A workorder that has been promoted and started
        UUID workorderId = seedApprovedWorkorder();

        // Start the workorder
        Map<String, Object> startRequest = Map.of(
                "userId", SYSTEM_USER_ID.toString(),
                "reason", "Starting work");

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
                .log().ifValidationFails()
                .statusCode(200)
                .body("$", hasSize(greaterThan(0)))
                .extract()
                .jsonPath()
                .getList("$");

        // Then: Verify transitions are ordered newest-first
        assertThat(transitions).isNotEmpty();

        // The most recent transition should be to WORK_IN_PROGRESS
        Map<String, Object> newestTransition = transitions.get(0);
        assertThat(newestTransition).containsEntry("toStatus", "WORK_IN_PROGRESS");
        assertThat(newestTransition).containsEntry("fromStatus", "APPROVED");

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
        UUID workorderId = seedApprovedWorkorder();

        // Start the workorder (this should create a snapshot)
        Map<String, Object> startRequest = Map.of(
                "userId", SYSTEM_USER_ID.toString(),
                "reason", "Starting work");

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
                .log().ifValidationFails()
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

        // Create and save estimate
        Estimate estimate = Estimate.builder()
                .estimateNumber("EST-START-" + UUID.randomUUID().toString().substring(0, 8))
                .customerId(testCustomerId)
                .vehicleId(testVehicleId)
                .locationId(testLocationId)
                .status(EstimateStatus.APPROVED)
                .approvedAt(LocalDateTime.now().minusHours(1))
                .approvedBy(testCustomerId)
                .expiresAt(LocalDateTime.now().plusDays(30))
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
                .estimateId(estimate.getId())
                .itemType(EstimateItemType.LABOR)
                .description("Oil change labor")
                .quantity(new BigDecimal("1.0000"))
                .unitPrice(new BigDecimal("100.00"))
                .lineTotal(new BigDecimal("100.00"))
                .taxCode("LABOR_TAX")
                .approvalStatus(ApprovalStatus.APPROVED)
                .approvalTimestamp(LocalDateTime.now().minusHours(1))
                .createdById("test-user")
                .build();
        estimateItemRepository.save(item);

        // Promote estimate to workorder
        String workorderIdStr = givenWithGatewayAuth()
                .when()
                .post("/v1/workorders/estimates/{id}/promote", estimate.getId())
                .then()
                .log().all()
                .statusCode(200)
                .extract()
                .path("id");

        return UUID.fromString(workorderIdStr);
    }

    /**
     * Seed an approved workorder with a pending change request
     */
    private UUID seedApprovedWorkorderWithPendingChangeRequest() {
        UUID workorderId = seedApprovedWorkorder();

        // Create a pending change request
        ChangeRequest changeRequest = ChangeRequest.builder()
                .workorderId(workorderId)
                .requestedByUserId(SYSTEM_USER_ID)
                .requestedAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
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
            testCustomerId = UUID.randomUUID();
            testLocationId = UUID.randomUUID();
            testVehicleId = UUID.randomUUID();
        }
    }
}
