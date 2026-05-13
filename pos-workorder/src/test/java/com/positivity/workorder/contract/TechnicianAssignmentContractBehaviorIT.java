package com.positivity.workorder.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;

import com.positivity.workorder.internal.entity.Estimate;
import com.positivity.workorder.internal.entity.EstimateItem;
import com.positivity.workorder.internal.entity.EstimateItemType;
import com.positivity.workorder.internal.entity.TechnicianAssignment;
import com.positivity.workorder.internal.entity.Workorder;
import com.positivity.workorder.internal.enums.ApprovalStatus;
import com.positivity.workorder.internal.enums.EstimateStatus;
import com.positivity.workorder.internal.enums.WorkorderStatus;
import com.positivity.workorder.internal.repository.EstimateItemRepository;
import com.positivity.workorder.internal.repository.EstimateRepository;
import com.positivity.workorder.internal.repository.TechnicianAssignmentRepository;
import com.positivity.workorder.internal.repository.WorkorderRepository;
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
 * Contract behavior integration tests for technician assignment to workorders.
 * CAP:005 Story #161 - Assign Technician to Workorder
 *
 * <p>
 * Tests cover:
 * <ul>
 * <li>TA-001: Happy path assign technician to APPROVED workorder</li>
 * <li>TA-002: Reassign technician with reason</li>
 * <li>TA-003: View assignment history</li>
 * <li>TA-004: Error - invalid workorder status</li>
 * </ul>
 */
@DisplayName("Technician Assignment Contract Behavior Tests (CAP:005 Story #161)")
@Import(ContractTestConfiguration.class)
class TechnicianAssignmentContractBehaviorIT extends BaseContractIntegrationTest {
    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);

    @Autowired
    private EstimateRepository estimateRepository;

    @Autowired
    private EstimateItemRepository estimateItemRepository;

    @Autowired
    private WorkorderRepository workorderRepository;

    @Autowired
    private TechnicianAssignmentRepository assignmentRepository;

    private UUID testCustomerId;
    private UUID testLocationId;
    private UUID testVehicleId;
    private UUID testTechnicianId1;
    private UUID testTechnicianId2;

    @AfterEach
    void tearDown() {
        purgeTestData();
    }

    // ========== TECHNICIAN ASSIGNMENT TESTS ==========

    @Test
    @DisplayName("TA-001: Successfully assign technician to APPROVED workorder")
    void testAssignTechnician_HappyPath() {
        // Given: A workorder in APPROVED status
        UUID workorderId = seedApprovedWorkorder();
        testTechnicianId1 = UUID.fromString("00000000-0000-0000-0000-000000000001");

        Workorder workorder = workorderRepository.findById(workorderId).orElseThrow();
        assertThat(workorder.getStatus()).isEqualTo(WorkorderStatus.APPROVED);

        // When: Assign a technician to the workorder
        Map<String, Object> assignRequest = Map.of(
                "technicianId",
                testTechnicianId1.toString(),
                "assignedByUserId",
                SYSTEM_USER_ID,
                "notes",
                "Assigned to senior tech for brake system work");

        givenWithGatewayAuth()
                .contentType(ContentType.JSON)
                .body(assignRequest)
                .when()
                .post("/v1/workorders/{workorderId}/technician", workorderId)
                .then()
                .log()
                .ifValidationFails()
                .statusCode(200)
                .body("workorderId", equalTo(workorderId.toString()))
                .body("technicianId", equalTo(testTechnicianId1.toString()))
                .body("assignedAt", notNullValue())
                .body("assignedBy", equalTo(SYSTEM_USER_ID))
                .body("status", equalTo("ASSIGNED"))
                .body("message", containsString("successfully"));

        // Then: Verify workorder status transitioned to ASSIGNED
        Workorder updatedWorkorder = workorderRepository.findById(workorderId).orElseThrow();
        assertThat(updatedWorkorder.getStatus()).isEqualTo(WorkorderStatus.ASSIGNED);

        // Verify assignment was recorded
        List<TechnicianAssignment> assignments =
                assignmentRepository.findByWorkorder_IdOrderByAssignedAtDesc(workorderId);
        assertThat(assignments).hasSize(1);

        TechnicianAssignment assignment = assignments.get(0);
        assertThat(assignment.getTechnicianId()).isEqualTo(testTechnicianId1);
        assertThat(assignment.getWorkorderId()).isEqualTo(workorderId);
        assertThat(assignment.getAssignedBy()).isEqualTo(SYSTEM_USER_ID);
        assertThat(assignment.getCurrent()).isTrue();
        assertThat(assignment.getUnassignedAt()).isNull();
        assertThat(assignment.getNotes()).isEqualTo("Assigned to senior tech for brake system work");
    }

    @Test
    @DisplayName("TA-002: Successfully reassign technician with reason")
    void testReassignTechnician_WithReason() {
        // Given: A workorder with an existing technician assignment
        UUID workorderId = seedWorkorderWithAssignedTechnician();
        testTechnicianId2 = UUID.fromString("00000000-0000-0000-0000-000000000002");

        // Verify initial assignment
        TechnicianAssignment initialAssignment = assignmentRepository
                .findByWorkorder_IdAndCurrentTrue(workorderId)
                .orElseThrow();
        UUID previousTechId = initialAssignment.getTechnicianId();
        assertThat(initialAssignment.getCurrent()).isTrue();

        // When: Reassign to a different technician
        Map<String, Object> reassignRequest = Map.of(
                "newTechnicianId",
                testTechnicianId2.toString(),
                "reassignedByUserId",
                SYSTEM_USER_ID,
                "reason",
                "Original technician unavailable, reassigning to available tech",
                "notes",
                "Customer requested different technician",
                "notifyPreviousTechnician",
                true);

        givenWithGatewayAuth()
                .contentType(ContentType.JSON)
                .body(reassignRequest)
                .when()
                .put("/v1/workorders/{workorderId}/technician", workorderId)
                .then()
                .log()
                .ifValidationFails()
                .statusCode(200)
                .body("workorderId", equalTo(workorderId.toString()))
                .body("technicianId", equalTo(testTechnicianId2.toString()))
                .body("previousTechnicianId", anyOf(equalTo(previousTechId.toString()), nullValue()))
                .body("reassignmentReason", equalTo("Original technician unavailable, reassigning to available tech"))
                .body("reassignedAt", notNullValue())
                .body("reassignedBy", equalTo(SYSTEM_USER_ID))
                .body("message", containsString("successfully"));

        // Then: Verify new assignment is current
        TechnicianAssignment currentAssignment = assignmentRepository
                .findByWorkorder_IdAndCurrentTrue(workorderId)
                .orElseThrow();
        assertThat(currentAssignment.getTechnicianId()).isEqualTo(testTechnicianId2);
        assertThat(currentAssignment.getCurrent()).isTrue();

        // Verify previous assignment is marked as not current
        List<TechnicianAssignment> allAssignments =
                assignmentRepository.findByWorkorder_IdOrderByAssignedAtDesc(workorderId);
        assertThat(allAssignments).hasSize(2);

        TechnicianAssignment previousAssignment = allAssignments.stream()
                .filter(a -> a.getTechnicianId().equals(previousTechId))
                .findFirst()
                .orElseThrow();
        assertThat(previousAssignment.getCurrent()).isFalse();
        assertThat(previousAssignment.getUnassignedAt()).isNotNull();
        assertThat(previousAssignment.getReassignmentReason()).contains("Original technician unavailable");
    }

    @Test
    @DisplayName("TA-003: Successfully retrieve assignment history")
    void testGetAssignmentHistory_MultipleAssignments() {
        // Given: A workorder with multiple assignment history
        UUID workorderId = seedWorkorderWithReassignmentHistory();

        // When: Retrieve assignment history
        givenWithGatewayAuth()
                .when()
                .get("/v1/workorders/{workorderId}/technician", workorderId)
                .then()
                .log()
                .ifValidationFails()
                .statusCode(200)
                .body("workorderId", equalTo(workorderId.toString()))
                .body("technicianId", notNullValue())
                .body("assignedAt", notNullValue())
                .body("currentStatus", notNullValue())
                .body("assignmentHistory", notNullValue())
                .body("assignmentHistory", hasSize(greaterThanOrEqualTo(2)))
                .body("assignmentHistory[0].technicianId", notNullValue())
                .body("assignmentHistory[0].assignedAt", notNullValue())
                .body("assignmentHistory[0].assignedBy", notNullValue());

        // Then: Verify history is ordered newest-first
        List<TechnicianAssignment> history = assignmentRepository.findByWorkorder_IdOrderByAssignedAtDesc(workorderId);
        assertThat(history).hasSizeGreaterThanOrEqualTo(2);

        // Verify most recent is first
        LocalDateTime previousTimestamp = history.get(0).getAssignedAt();
        for (int i = 1; i < history.size(); i++) {
            LocalDateTime currentTimestamp = history.get(i).getAssignedAt();
            assertThat(currentTimestamp).isBeforeOrEqualTo(previousTimestamp);
            previousTimestamp = currentTimestamp;
        }
    }

    @Test
    @DisplayName("TA-004: Reject assignment if workorder status is COMPLETED")
    void testAssignTechnician_InvalidStatus() {
        // Given: A workorder in COMPLETED status
        UUID workorderId = seedCompletedWorkorder();
        testTechnicianId1 = UUID.fromString("00000000-0000-0000-0000-000000000001");

        Workorder workorder = workorderRepository.findById(workorderId).orElseThrow();
        assertThat(workorder.getStatus()).isEqualTo(WorkorderStatus.COMPLETED);

        // When: Attempt to assign a technician
        Map<String, Object> assignRequest = Map.of(
                "technicianId",
                testTechnicianId1.toString(),
                "assignedByUserId",
                SYSTEM_USER_ID,
                "notes",
                "Attempting to assign to completed workorder");

        givenWithGatewayAuth()
                .contentType(ContentType.JSON)
                .body(assignRequest)
                .when()
                .post("/v1/workorders/{workorderId}/technician", workorderId)
                .then()
                .log()
                .ifValidationFails()
                .statusCode(400); // Bad request due to invalid state

        // Then: Verify no assignment was created
        List<TechnicianAssignment> assignments =
                assignmentRepository.findByWorkorder_IdOrderByAssignedAtDesc(workorderId);
        assertThat(assignments).isEmpty();
    }

    @Test
    @DisplayName("TA-005: Reject reassignment if no current assignment exists")
    void testReassignTechnician_NoExistingAssignment() {
        // Given: A workorder with NO existing assignment
        UUID workorderId = seedApprovedWorkorder();
        testTechnicianId1 = UUID.fromString("00000000-0000-0000-0000-000000000001");

        // Verify no current assignment
        assertThat(assignmentRepository.findByWorkorder_IdAndCurrentTrue(workorderId))
                .isEmpty();

        // When: Attempt to reassign
        Map<String, Object> reassignRequest = Map.of(
                "newTechnicianId",
                testTechnicianId1.toString(),
                "reassignedByUserId",
                SYSTEM_USER_ID,
                "reason",
                "Attempting reassignment without existing assignment");

        givenWithGatewayAuth()
                .contentType(ContentType.JSON)
                .body(reassignRequest)
                .when()
                .put("/v1/workorders/{workorderId}/technician", workorderId)
                .then()
                .log()
                .ifValidationFails()
                .statusCode(400); // Bad request - no current assignment to reassign from

        // Then: Verify no assignment was created
        List<TechnicianAssignment> assignments =
                assignmentRepository.findByWorkorder_IdOrderByAssignedAtDesc(workorderId);
        assertThat(assignments).isEmpty();
    }

    @Test
    @DisplayName("TA-006: Return 404 when retrieving assignment for non-existent workorder")
    void testGetAssignment_WorkorderNotFound() {
        // Given: A non-existent workorder ID
        UUID nonExistentId = UUID.fromString("00000000-0000-0000-0000-000000000001");

        // When/Then: Attempt to retrieve assignment
        givenWithGatewayAuth()
                .when()
                .get("/v1/workorders/{workorderId}/technician", nonExistentId)
                .then()
                .log()
                .ifValidationFails()
                .statusCode(404);
    }

    // ========== TEST DATA SEED METHODS ==========

    /**
     * Seed an APPROVED workorder ready for technician assignment.
     */
    private UUID seedApprovedWorkorder() {
        testCustomerId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        testLocationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        testVehicleId = UUID.fromString("00000000-0000-0000-0000-000000000001");

        // Create an approved estimate
        Estimate estimate = Estimate.builder()
                .customerId(testCustomerId)
                .locationId(testLocationId)
                .vehicleId(testVehicleId)
                .status(EstimateStatus.APPROVED)
                .approvedBy(testCustomerId)
                .createdById("test-user")
                .createdByUserId("test-user")
                .build();
        estimate = estimateRepository.save(estimate);

        // Add approved items
        EstimateItem item = EstimateItem.builder()
                .estimate(estimate)
                .itemType(EstimateItemType.LABOR)
                .description("Oil change")
                .quantity(BigDecimal.ONE)
                .unitPrice(BigDecimal.valueOf(50.00))
                .approvalStatus(ApprovalStatus.APPROVED)
                .createdById("test-user")
                .build();
        estimateItemRepository.save(item);

        // Create workorder in APPROVED status
        Workorder workorder = Workorder.builder()
                .estimate(estimate)
                .customerId(testCustomerId)
                .vehicleId(testVehicleId)
                .status(WorkorderStatus.APPROVED)
                .build();
        workorder = workorderRepository.save(workorder);

        return workorder.getId();
    }

    /**
     * Seed a workorder with an assigned technician.
     */
    private UUID seedWorkorderWithAssignedTechnician() {
        UUID workorderId = seedApprovedWorkorder();
        testTechnicianId1 = UUID.fromString("00000000-0000-0000-0000-000000000001");

        // Create initial assignment
        LocalDateTime now = LocalDateTime.now(TEST_CLOCK);
        TechnicianAssignment assignment = TechnicianAssignment.builder()
                .workorder(new Workorder(workorderId))
                .technicianId(testTechnicianId1)
                .assignedBy(SYSTEM_USER_ID)
                .assignedAt(now.minusHours(2))
                .notes("Initial assignment")
                .current(true)
                .createdAt(now.minusHours(2).toInstant(ZoneOffset.UTC))
                .updatedAt(now.minusHours(2).toInstant(ZoneOffset.UTC))
                .build();
        assignmentRepository.save(assignment);

        // Update workorder status to ASSIGNED
        Workorder workorder = workorderRepository.findById(workorderId).orElseThrow();
        workorder.setStatus(WorkorderStatus.ASSIGNED);
        workorderRepository.save(workorder);

        return workorderId;
    }

    /**
     * Seed a workorder with reassignment history.
     */
    private UUID seedWorkorderWithReassignmentHistory() {
        UUID workorderId = seedApprovedWorkorder();
        testTechnicianId1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
        testTechnicianId2 = UUID.fromString("00000000-0000-0000-0000-000000000002");

        LocalDateTime now = LocalDateTime.now(TEST_CLOCK);

        // Create first assignment (now inactive)
        TechnicianAssignment firstAssignment = TechnicianAssignment.builder()
                .workorder(new Workorder(workorderId))
                .technicianId(testTechnicianId1)
                .assignedBy(SYSTEM_USER_ID)
                .assignedAt(now.minusDays(2))
                .unassignedAt(now.minusDays(1))
                .reassignmentReason("Technician called out sick")
                .notes("First assignment")
                .current(false)
                .createdAt(now.minusDays(2).toInstant(ZoneOffset.UTC))
                .updatedAt(now.minusDays(1).toInstant(ZoneOffset.UTC))
                .build();
        assignmentRepository.save(firstAssignment);

        // Create second assignment (current)
        TechnicianAssignment currentAssignment = TechnicianAssignment.builder()
                .workorder(new Workorder(workorderId))
                .technicianId(testTechnicianId2)
                .assignedBy(SYSTEM_USER_ID)
                .assignedAt(now.minusDays(1))
                .notes("Reassigned to available technician")
                .current(true)
                .createdAt(now.minusDays(1).toInstant(ZoneOffset.UTC))
                .updatedAt(now.minusDays(1).toInstant(ZoneOffset.UTC))
                .build();
        assignmentRepository.save(currentAssignment);

        // Update workorder status
        Workorder workorder = workorderRepository.findById(workorderId).orElseThrow();
        workorder.setStatus(WorkorderStatus.WORK_IN_PROGRESS);
        workorderRepository.save(workorder);

        return workorderId;
    }

    /**
     * Seed a COMPLETED workorder for testing invalid state transitions.
     */
    private UUID seedCompletedWorkorder() {
        UUID workorderId = seedApprovedWorkorder();

        Workorder workorder = workorderRepository.findById(workorderId).orElseThrow();
        workorder.setStatus(WorkorderStatus.COMPLETED);
        workorder = workorderRepository.save(workorder);

        return workorder.getId();
    }
}
