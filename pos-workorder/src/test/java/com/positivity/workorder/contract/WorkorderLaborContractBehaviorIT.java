package com.positivity.workorder.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;

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

import com.positivity.workorder.internal.entity.*;
import com.positivity.workorder.internal.repository.*;
import com.positivity.workorder.support.BaseContractIntegrationTest;

import io.restassured.http.ContentType;

/**
 * Contract behavior integration tests for labor tracking on workorders.
 * CAP:005 Story #159 - Record Labor Performed
 *
 * <p>Tests cover:
 * <ul>
 *   <li>WL-001: Happy path start labor session</li>
 *   <li>WL-002: Start labor with idempotency (duplicate key returns existing)</li>
 *   <li>WL-003: Reject start labor if workorder not in progress</li>
 *   <li>WL-004: Happy path stop labor session</li>
 *   <li>WL-005: Stop labor with idempotency</li>
 *   <li>WL-006: Adjust labor hours manually</li>
 *   <li>WL-007: Get labor history returns newest-first order</li>
 * </ul>
 */
@DisplayName("Labor Tracking Contract Behavior Tests (CAP:005 Story #159)")
@Import(ContractTestConfiguration.class)
class WorkorderLaborContractBehaviorIT extends BaseContractIntegrationTest {

    @Autowired
    private EstimateRepository estimateRepository;

    @Autowired
    private EstimateItemRepository estimateItemRepository;

    @Autowired
    private WorkorderRepository workorderRepository;

    @Autowired
    private WorkorderServiceRepository workorderServiceRepository;

    @Autowired
    private WorkorderLaborEntryRepository laborEntryRepository;

    private UUID testCustomerId;
    private UUID testLocationId;
    private UUID testVehicleId;
    private UUID testTechnicianId;

    @AfterEach
    void tearDown() {
        laborEntryRepository.deleteAll();
        workorderServiceRepository.deleteAll();
        workorderRepository.deleteAll();
        estimateItemRepository.deleteAll();
        estimateRepository.deleteAll();
    }

    // ========== LABOR TRACKING TESTS ==========

    @Test
    @DisplayName("WL-001: Successfully start labor session on a service")
    void testStartLaborSession_HappyPath() {
        // Given: A workorder in WORK_IN_PROGRESS status with a service line
        UUID workorderId = seedWorkInProgressWorkorder();
        UUID serviceId = workorderServiceRepository.findAll().get(0).getId();
        testTechnicianId = UUID.randomUUID();

        // When: Start a labor session
        Map<String, Object> startRequest = Map.of(
                "technicianId", testTechnicianId.toString(),
                "notes", "Beginning brake pad replacement"
        );

        givenWithGatewayAuth()
                .contentType(ContentType.JSON)
                .body(startRequest)
                .when()
                .post("/v1/workorders/{workorderId}/services/{serviceId}/labor/start", workorderId, serviceId)
                .then()
                .log().ifValidationFails()
                .statusCode(201)
                .body("workorderId", equalTo(workorderId.toString()))
                .body("workorderServiceId", equalTo(serviceId.toString()))
                .body("technicianId", equalTo(testTechnicianId.toString()))
                .body("startTime", notNullValue())
                .body("endTime", nullValue())
                .body("hoursWorked", equalTo("0.00"))
                .body("active", equalTo(true))
                .body("notes", equalTo("Beginning brake pad replacement"));

        // Then: Verify labor entry was created
        List<WorkorderLaborEntry> entries = laborEntryRepository.findByWorkorderIdOrderByStartTimeDesc(workorderId);
        assertThat(entries).hasSize(1);
        
        WorkorderLaborEntry entry = entries.get(0);
        assertThat(entry.getWorkorderServiceId()).isEqualTo(serviceId);
        assertThat(entry.getTechnicianId()).isEqualTo(testTechnicianId);
        assertThat(entry.isActive()).isTrue();
        assertThat(entry.getHoursWorked()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("WL-002: Start labor with idempotency returns existing session")
    void testStartLaborSession_Idempotency() {
        // Given: A workorder with an active labor session
        UUID workorderId = seedWorkInProgressWorkorder();
        UUID serviceId = workorderServiceRepository.findAll().get(0).getId();
        testTechnicianId = UUID.randomUUID();
        String idempotencyKey = "test-start-labor-" + UUID.randomUUID();

        Map<String, Object> startRequest = Map.of(
                "technicianId", testTechnicianId.toString(),
                "notes", "First attempt"
        );

        // When: First request creates the session
        String firstResponseId = givenWithGatewayAuth()
                .contentType(ContentType.JSON)
                .header("Idempotency-Key", idempotencyKey)
                .body(startRequest)
                .when()
                .post("/v1/workorders/{workorderId}/services/{serviceId}/labor/start", workorderId, serviceId)
                .then()
                .statusCode(201)
                .extract().path("id");

        // Then: Second request with same key returns existing session (200)
        givenWithGatewayAuth()
                .contentType(ContentType.JSON)
                .header("Idempotency-Key", idempotencyKey)
                .body(startRequest)
                .when()
                .post("/v1/workorders/{workorderId}/services/{serviceId}/labor/start", workorderId, serviceId)
                .then()
                .log().ifValidationFails()
                .statusCode(200)
                .body("id", equalTo(firstResponseId))
                .body("technicianId", equalTo(testTechnicianId.toString()));

        // Verify only one entry exists
        List<WorkorderLaborEntry> entries = laborEntryRepository.findByWorkorderIdOrderByStartTimeDesc(workorderId);
        assertThat(entries).hasSize(1);
    }

    @Test
    @DisplayName("WL-003: Reject start labor if workorder not in progress")
    void testStartLaborSession_InvalidStatus() {
        // Given: A workorder in COMPLETED status
        UUID workorderId = seedCompletedWorkorder();
        UUID serviceId = workorderServiceRepository.findAll().get(0).getId();
        testTechnicianId = UUID.randomUUID();

        // When: Attempt to start labor
        Map<String, Object> startRequest = Map.of(
                "technicianId", testTechnicianId.toString()
        );

        // Then: Request is rejected with 400
        givenWithGatewayAuth()
                .contentType(ContentType.JSON)
                .body(startRequest)
                .when()
                .post("/v1/workorders/{workorderId}/services/{serviceId}/labor/start", workorderId, serviceId)
                .then()
                .log().ifValidationFails()
                .statusCode(400);

        // Verify no labor entry was created
        List<WorkorderLaborEntry> entries = laborEntryRepository.findByWorkorderIdOrderByStartTimeDesc(workorderId);
        assertThat(entries).isEmpty();
    }

    @Test
    @DisplayName("WL-004: Successfully stop labor session and calculate hours")
    void testStopLaborSession_HappyPath() {
        // Given: A workorder with an active labor session
        UUID workorderId = seedWorkorderWithActiveLaborSession();
        UUID entryId = laborEntryRepository.findByWorkorderIdOrderByStartTimeDesc(workorderId).get(0).getId();

        // When: Stop the labor session
        givenWithGatewayAuth()
                .contentType(ContentType.JSON)
                .when()
                .post("/v1/workorders/{workorderId}/labor/{entryId}/stop", workorderId, entryId)
                .then()
                .log().ifValidationFails()
                .statusCode(200)
                .body("id", equalTo(entryId.toString()))
                .body("endTime", notNullValue())
                .body("active", equalTo(false))
                .body("hoursWorked", not(equalTo("0.00")));

        // Then: Verify labor entry was stopped
        WorkorderLaborEntry entry = laborEntryRepository.findById(entryId).orElseThrow();
        assertThat(entry.isActive()).isFalse();
        assertThat(entry.getEndTime()).isNotNull();
        assertThat(entry.getHoursWorked()).isGreaterThan(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("WL-005: Stop labor with idempotency returns existing stopped session")
    void testStopLaborSession_Idempotency() {
        // Given: A workorder with an active labor session
        UUID workorderId = seedWorkorderWithActiveLaborSession();
        UUID entryId = laborEntryRepository.findByWorkorderIdOrderByStartTimeDesc(workorderId).get(0).getId();
        String idempotencyKey = "test-stop-labor-" + UUID.randomUUID();

        // When: First request stops the session
        String hoursWorkedStr = givenWithGatewayAuth()
                .contentType(ContentType.JSON)
                .header("Idempotency-Key", idempotencyKey)
                .when()
                .post("/v1/workorders/{workorderId}/labor/{entryId}/stop", workorderId, entryId)
                .then()
                .statusCode(200)
                .extract().path("hoursWorked");
        
        BigDecimal firstHoursWorked = new BigDecimal(hoursWorkedStr);

        // Then: Second request with same key returns same result
        givenWithGatewayAuth()
                .contentType(ContentType.JSON)
                .header("Idempotency-Key", idempotencyKey)
                .when()
                .post("/v1/workorders/{workorderId}/labor/{entryId}/stop", workorderId, entryId)
                .then()
                .log().ifValidationFails()
                .statusCode(200)
                .body("id", equalTo(entryId.toString()))
                .body("active", equalTo(false))
                .body("hoursWorked", equalTo(firstHoursWorked.toString()));
    }

    @Test
    @DisplayName("WL-006: Successfully adjust labor hours manually")
    void testAdjustLaborHours_ManualOverride() {
        // Given: A stopped labor session
        UUID workorderId = seedWorkorderWithStoppedLaborSession();
        UUID entryId = laborEntryRepository.findByWorkorderIdOrderByStartTimeDesc(workorderId).get(0).getId();

        // When: Manually adjust the hours
        Map<String, Object> adjustRequest = Map.of(
                "hoursWorked", "3.5",
                "adjustmentReason", "Manual correction for unpaid break time"
        );

        givenWithGatewayAuth()
                .contentType(ContentType.JSON)
                .body(adjustRequest)
                .when()
                .put("/v1/workorders/{workorderId}/labor/{entryId}/adjust", workorderId, entryId)
                .then()
                .log().ifValidationFails()
                .statusCode(200)
                .body("id", equalTo(entryId.toString()))
                .body("hoursWorked", equalTo("3.50"))
                .body("adjustmentReason", equalTo("Manual correction for unpaid break time"));

        // Then: Verify adjustment was recorded
        WorkorderLaborEntry entry = laborEntryRepository.findById(entryId).orElseThrow();
        assertThat(entry.getHoursWorked()).isEqualByComparingTo(new BigDecimal("3.50"));
        assertThat(entry.getAdjustmentReason()).isEqualTo("Manual correction for unpaid break time");
    }

    @Test
    @DisplayName("WL-007: Get labor history returns newest-first order")
    void testGetLaborHistory_NewestFirst() {
        // Given: A workorder with multiple labor entries
        UUID workorderId = seedWorkorderWithMultipleLaborEntries();

        // When: Retrieve labor history
        List<String> startTimes = givenWithGatewayAuth()
                .when()
                .get("/v1/workorders/{workorderId}/labor", workorderId)
                .then()
                .log().ifValidationFails()
                .statusCode(200)
                .body("size()", greaterThanOrEqualTo(2))
                .extract().jsonPath().getList("startTime", String.class);

        // Then: Entries are ordered newest-first (verify first is after second)
        assertThat(startTimes).hasSizeGreaterThanOrEqualTo(2);
        LocalDateTime first = LocalDateTime.parse(startTimes.get(0));
        LocalDateTime second = LocalDateTime.parse(startTimes.get(1));
        assertThat(first).isAfter(second);
        
        List<WorkorderLaborEntry> entries = laborEntryRepository.findByWorkorderIdOrderByStartTimeDesc(workorderId);
        assertThat(entries).hasSizeGreaterThanOrEqualTo(2);
    }

    // ========== TEST DATA SEED METHODS ==========

    /**
     * Seed a workorder in WORK_IN_PROGRESS status with a service line.
     */
    private UUID seedWorkInProgressWorkorder() {
        testCustomerId = UUID.randomUUID();
        testLocationId = UUID.randomUUID();
        testVehicleId = UUID.randomUUID();

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

        // Add approved service item
        EstimateItem item = EstimateItem.builder()
                .estimateId(estimate.getId())
                .itemType(EstimateItemType.LABOR)
                .description("Brake pad replacement")
                .quantity(BigDecimal.valueOf(2.0))
                .unitPrice(BigDecimal.valueOf(75.00))
                .approvalStatus(ApprovalStatus.APPROVED)
                .createdById("test-user")
                .build();
        estimateItemRepository.save(item);

        // Create workorder in WORK_IN_PROGRESS status
        Workorder workorder = Workorder.builder()
                .estimateId(estimate.getId())
                .customerId(testCustomerId)
                .vehicleId(testVehicleId)
                .status(WorkorderStatus.WORK_IN_PROGRESS)
                .build();
        workorder = workorderRepository.save(workorder);

        // Add service line to workorder
        WorkorderService service = WorkorderService.builder()
                .workOrder(workorder)
                .description("Brake pad replacement")
                .quantity(BigDecimal.valueOf(2.0))
                .unitPrice(BigDecimal.valueOf(75.00))
                .lineTotal(BigDecimal.valueOf(150.00))
                .status(WorkorderItemStatus.OPEN)
                .originEstimateItemId(item.getId())
                .build();
        workorderServiceRepository.save(service);

        return workorder.getId();
    }

    /**
     * Seed a workorder with an active labor session.
     */
    private UUID seedWorkorderWithActiveLaborSession() {
        UUID workorderId = seedWorkInProgressWorkorder();
        UUID serviceId = workorderServiceRepository.findAll().get(0).getId();
        testTechnicianId = UUID.randomUUID();

        Workorder workorder = workorderRepository.findById(workorderId).orElseThrow();

        // Create active labor entry (started 1 hour ago)
        WorkorderLaborEntry entry = WorkorderLaborEntry.builder()
                .workorder(workorder)
                .workorderId(workorderId)
                .workorderServiceId(serviceId)
                .technicianId(testTechnicianId)
                .startTime(LocalDateTime.now().minusHours(1))
                .hoursWorked(BigDecimal.ZERO)
                .notes("Test active session")
                .createdBy(SYSTEM_USER_ID)
                .build();
        entry.generateId(); // Generate ID and set createdAt
        laborEntryRepository.save(entry);

        return workorderId;
    }

    /**
     * Seed a workorder with a stopped labor session.
     */
    private UUID seedWorkorderWithStoppedLaborSession() {
        UUID workorderId = seedWorkInProgressWorkorder();
        UUID serviceId = workorderServiceRepository.findAll().get(0).getId();
        testTechnicianId = UUID.randomUUID();

        Workorder workorder = workorderRepository.findById(workorderId).orElseThrow();

        // Create stopped labor entry (2 hours duration)
        WorkorderLaborEntry entry = WorkorderLaborEntry.builder()
                .workorder(workorder)
                .workorderId(workorderId)
                .workorderServiceId(serviceId)
                .technicianId(testTechnicianId)
                .startTime(LocalDateTime.now().minusHours(3))
                .endTime(LocalDateTime.now().minusHours(1))
                .hoursWorked(BigDecimal.valueOf(2.0))
                .notes("Test stopped session")
                .createdBy(SYSTEM_USER_ID)
                .build();
        entry.generateId(); // Generate ID and set createdAt
        laborEntryRepository.save(entry);

        return workorderId;
    }

    /**
     * Seed a workorder with multiple labor entries for history testing.
     */
    private UUID seedWorkorderWithMultipleLaborEntries() {
        UUID workorderId = seedWorkInProgressWorkorder();
        UUID serviceId = workorderServiceRepository.findAll().get(0).getId();
        testTechnicianId = UUID.randomUUID();

        Workorder workorder = workorderRepository.findById(workorderId).orElseThrow();

        // Create first entry (older)
        WorkorderLaborEntry entry1 = WorkorderLaborEntry.builder()
                .workorder(workorder)
                .workorderId(workorderId)
                .workorderServiceId(serviceId)
                .technicianId(testTechnicianId)
                .startTime(LocalDateTime.now().minusDays(2))
                .endTime(LocalDateTime.now().minusDays(2).plusHours(2))
                .hoursWorked(BigDecimal.valueOf(2.0))
                .notes("First session")
                .createdBy(SYSTEM_USER_ID)
                .build();
        entry1.generateId(); // Generate ID and set createdAt
        laborEntryRepository.save(entry1);

        // Create second entry (newer)
        WorkorderLaborEntry entry2 = WorkorderLaborEntry.builder()
                .workorder(workorder)
                .workorderId(workorderId)
                .workorderServiceId(serviceId)
                .technicianId(testTechnicianId)
                .startTime(LocalDateTime.now().minusDays(1))
                .endTime(LocalDateTime.now().minusDays(1).plusHours(3))
                .hoursWorked(BigDecimal.valueOf(3.0))
                .notes("Second session")
                .createdBy(SYSTEM_USER_ID)
                .build();
        entry2.generateId(); // Generate ID and set createdAt
        laborEntryRepository.save(entry2);

        return workorderId;
    }

    /**
     * Seed a COMPLETED workorder for testing invalid state.
     */
    private UUID seedCompletedWorkorder() {
        UUID workorderId = seedWorkInProgressWorkorder();
        
        Workorder workorder = workorderRepository.findById(workorderId).orElseThrow();
        workorder.setStatus(WorkorderStatus.COMPLETED);
        workorderRepository.save(workorder);

        return workorderId;
    }
}
