package com.positivity.workorder.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import java.math.BigDecimal;
import java.math.RoundingMode;
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

import com.positivity.workorder.internal.entity.Estimate;
import com.positivity.workorder.internal.entity.EstimateItem;
import com.positivity.workorder.internal.entity.EstimateItemType;
import com.positivity.workorder.internal.entity.Workorder;
import com.positivity.workorder.internal.entity.WorkorderLaborEntry;
import com.positivity.workorder.internal.entity.WorkorderService;
import com.positivity.workorder.internal.enums.ApprovalStatus;
import com.positivity.workorder.internal.enums.EstimateStatus;
import com.positivity.workorder.internal.enums.WorkorderItemStatus;
import com.positivity.workorder.internal.enums.WorkorderStatus;
import com.positivity.workorder.internal.repository.EstimateItemRepository;
import com.positivity.workorder.internal.repository.EstimateRepository;
import com.positivity.workorder.internal.repository.WorkorderLaborEntryRepository;
import com.positivity.workorder.internal.repository.WorkorderRepository;
import com.positivity.workorder.internal.repository.WorkorderServiceRepository;
import com.positivity.workorder.support.BaseContractIntegrationTest;

import io.restassured.http.ContentType;
import io.restassured.response.Response;

/**
 * Contract behavior integration tests for labor tracking on workorders.
 * CAP:005 Story #159 - Record Labor Performed
 *
 * <p>
 * Tests cover:
 * <ul>
 * <li>WL-001: Happy path start labor session</li>
 * <li>WL-002: Start labor with idempotency (duplicate key returns
 * existing)</li>
 * <li>WL-003: Reject start labor if workorder not in progress</li>
 * <li>WL-004: Happy path stop labor session</li>
 * <li>WL-005: Stop labor with idempotency</li>
 * <li>WL-006: Adjust labor hours manually</li>
 * <li>WL-007: Get labor history returns newest-first order</li>
 * </ul>
 */
@DisplayName("Labor Tracking Contract Behavior Tests (CAP:005 Story #159)")
@Import(ContractTestConfiguration.class)
class WorkorderLaborContractBehaviorIT extends BaseContractIntegrationTest {
        private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);

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
                testTechnicianId = UUID.fromString("00000000-0000-0000-0000-000000000001");

                // When: Start a labor session
                Map<String, Object> startRequest = Map.of(
                                "technicianId", testTechnicianId.toString(),
                                "notes", "Beginning brake pad replacement");

                Response response = givenWithGatewayAuth()
                                .contentType(ContentType.JSON)
                                .body(startRequest)
                                .when()
                                .post("/v1/workorders/{workorderId}/services/{serviceId}/labor/start", workorderId,
                                                serviceId)
                                .then()
                                .log().ifValidationFails()
                                .statusCode(201)
                                .body("workorderId", equalTo(workorderId.toString()))
                                .body("workorderServiceId", equalTo(serviceId.toString()))
                                .body("technicianId", equalTo(testTechnicianId.toString()))
                                .body("startTime", notNullValue())
                                .body("endTime", nullValue())
                                .body("active", equalTo(true))
                                .body("notes", equalTo("Beginning brake pad replacement"))
                                .extract().response();

                BigDecimal hoursWorked = decimalValue(response, "hoursWorked");
                assertThat(hoursWorked).isEqualByComparingTo(BigDecimal.ZERO);

                // Then: Verify labor entry was created
                List<WorkorderLaborEntry> entries = laborEntryRepository
                                .findByWorkorder_IdOrderByStartTimeDesc(workorderId);
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
                testTechnicianId = UUID.fromString("00000000-0000-0000-0000-000000000001");
                String idempotencyKey = "test-start-labor-" + UUID.fromString("00000000-0000-0000-0000-000000000001");

                Map<String, Object> startRequest = Map.of(
                                "technicianId", testTechnicianId.toString(),
                                "notes", "First attempt");

                // When: First request creates the session
                String firstResponseId = givenWithGatewayAuth()
                                .contentType(ContentType.JSON)
                                .header("Idempotency-Key", idempotencyKey)
                                .body(startRequest)
                                .when()
                                .post("/v1/workorders/{workorderId}/services/{serviceId}/labor/start", workorderId,
                                                serviceId)
                                .then()
                                .statusCode(anyOf(is(201), is(200)))
                                .extract().path("id");

                // Then: Second request with same key returns existing session (200)
                givenWithGatewayAuth()
                                .contentType(ContentType.JSON)
                                .header("Idempotency-Key", idempotencyKey)
                                .body(startRequest)
                                .when()
                                .post("/v1/workorders/{workorderId}/services/{serviceId}/labor/start", workorderId,
                                                serviceId)
                                .then()
                                .log().ifValidationFails()
                                .statusCode(200)
                                .body("id", equalTo(firstResponseId))
                                .body("technicianId", equalTo(testTechnicianId.toString()));

                // Verify only one entry exists
                List<WorkorderLaborEntry> entries = laborEntryRepository
                                .findByWorkorder_IdOrderByStartTimeDesc(workorderId);
                assertThat(entries).hasSize(1);
        }

        @Test
        @DisplayName("WL-003: Reject start labor if workorder not in progress")
        void testStartLaborSession_InvalidStatus() {
                // Given: A workorder in COMPLETED status
                UUID workorderId = seedCompletedWorkorder();
                UUID serviceId = workorderServiceRepository.findAll().get(0).getId();
                testTechnicianId = UUID.fromString("00000000-0000-0000-0000-000000000001");

                // When: Attempt to start labor
                Map<String, Object> startRequest = Map.of(
                                "technicianId", testTechnicianId.toString());

                // Then: Request is rejected with 400
                givenWithGatewayAuth()
                                .contentType(ContentType.JSON)
                                .body(startRequest)
                                .when()
                                .post("/v1/workorders/{workorderId}/services/{serviceId}/labor/start", workorderId,
                                                serviceId)
                                .then()
                                .log().ifValidationFails()
                                .statusCode(400);

                // Verify no labor entry was created
                List<WorkorderLaborEntry> entries = laborEntryRepository
                                .findByWorkorder_IdOrderByStartTimeDesc(workorderId);
                assertThat(entries).isEmpty();
        }

        @Test
        @DisplayName("WL-004: Successfully stop labor session and calculate hours")
        void testStopLaborSession_HappyPath() {
                // Given: A workorder with an active labor session
                UUID workorderId = seedWorkorderWithActiveLaborSession();
                UUID entryId = laborEntryRepository.findByWorkorder_IdOrderByStartTimeDesc(workorderId).get(0).getId();

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
                UUID entryId = laborEntryRepository.findByWorkorder_IdOrderByStartTimeDesc(workorderId).get(0).getId();
                String idempotencyKey = "test-stop-labor-" + UUID.fromString("00000000-0000-0000-0000-000000000001");

                // When: First request stops the session
                Number hoursWorkedValue = givenWithGatewayAuth()
                                .contentType(ContentType.JSON)
                                .header("Idempotency-Key", idempotencyKey)
                                .when()
                                .post("/v1/workorders/{workorderId}/labor/{entryId}/stop", workorderId, entryId)
                                .then()
                                .statusCode(200)
                                .extract().path("hoursWorked");

                BigDecimal firstHoursWorked = BigDecimal.valueOf(hoursWorkedValue.doubleValue());

                // Then: Second request with same key returns same result
                Response secondResponse = givenWithGatewayAuth()
                                .contentType(ContentType.JSON)
                                .header("Idempotency-Key", idempotencyKey)
                                .when()
                                .post("/v1/workorders/{workorderId}/labor/{entryId}/stop", workorderId, entryId)
                                .then()
                                .log().ifValidationFails()
                                .statusCode(200)
                                .body("id", equalTo(entryId.toString()))
                                .body("active", equalTo(false))
                                .extract().response();

                BigDecimal secondHoursWorked = decimalValue(secondResponse, "hoursWorked");
                assertThat(secondHoursWorked)
                                .isEqualByComparingTo(firstHoursWorked.setScale(2, RoundingMode.HALF_UP));
        }

        @Test
        @DisplayName("WL-006: Successfully adjust labor hours manually")
        void testAdjustLaborHours_ManualOverride() {
                // Given: A stopped labor session
                UUID workorderId = seedWorkorderWithStoppedLaborSession();
                UUID entryId = laborEntryRepository.findByWorkorder_IdOrderByStartTimeDesc(workorderId).get(0).getId();

                // When: Manually adjust the hours
                Map<String, Object> adjustRequest = Map.of(
                                "hoursWorked", "3.5",
                                "adjustmentReason", "Manual correction for unpaid break time");

                Response adjustResponse = givenWithGatewayAuth()
                                .contentType(ContentType.JSON)
                                .body(adjustRequest)
                                .when()
                                .put("/v1/workorders/{workorderId}/labor/{entryId}/adjust", workorderId, entryId)
                                .then()
                                .log().ifValidationFails()
                                .statusCode(200)
                                .body("id", equalTo(entryId.toString()))
                                .body("adjustmentReason", equalTo("Manual correction for unpaid break time"))
                                .extract().response();

                BigDecimal adjustedHours = decimalValue(adjustResponse, "hoursWorked");
                assertThat(adjustedHours).isEqualByComparingTo(new BigDecimal("3.50"));

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

                List<WorkorderLaborEntry> entries = laborEntryRepository
                                .findByWorkorder_IdOrderByStartTimeDesc(workorderId);
                assertThat(entries).hasSizeGreaterThanOrEqualTo(2);
        }

        // ========== TEST DATA SEED METHODS ==========

        /**
         * Seed a workorder in WORK_IN_PROGRESS status with a service line.
         */
        private UUID seedWorkInProgressWorkorder() {
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

                // Add approved service item
                EstimateItem item = EstimateItem.builder()
                                .estimate(estimate)
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
                                .estimate(estimate)
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
                                .originEstimateItem(item)
                                .build();
                workorderServiceRepository.save(service);

                return workorder.getId();
        }

        private BigDecimal decimalValue(Response response, String field) {
                Object raw = response.jsonPath().get(field);
                if (raw instanceof Number number) {
                        return BigDecimal.valueOf(number.doubleValue());
                }
                return new BigDecimal(String.valueOf(raw));
        }

        /**
         * Seed a workorder with an active labor session.
         */
        private UUID seedWorkorderWithActiveLaborSession() {
                UUID workorderId = seedWorkInProgressWorkorder();
                UUID serviceId = workorderServiceRepository.findAll().get(0).getId();
                testTechnicianId = UUID.fromString("00000000-0000-0000-0000-000000000001");

                Workorder workorder = workorderRepository.findById(workorderId).orElseThrow();

                // Create active labor entry (started 1 hour ago)
                WorkorderLaborEntry entry = WorkorderLaborEntry.builder()
                                .workorder(workorder)
                                .workorderService(new WorkorderService(serviceId))
                                .technicianId(testTechnicianId)
                                .startTime(LocalDateTime.now(TEST_CLOCK).minusHours(1))
                                .hoursWorked(BigDecimal.ZERO)
                                .notes("Test active session")
                                .createdBy(SYSTEM_USER_ID)
                                .createdAt(java.time.Instant.now(TEST_CLOCK))
                                .build();
                laborEntryRepository.save(entry);

                return workorderId;
        }

        /**
         * Seed a workorder with a stopped labor session.
         */
        private UUID seedWorkorderWithStoppedLaborSession() {
                UUID workorderId = seedWorkInProgressWorkorder();
                UUID serviceId = workorderServiceRepository.findAll().get(0).getId();
                testTechnicianId = UUID.fromString("00000000-0000-0000-0000-000000000001");

                Workorder workorder = workorderRepository.findById(workorderId).orElseThrow();

                // Create stopped labor entry (2 hours duration)
                WorkorderLaborEntry entry = WorkorderLaborEntry.builder()
                                .workorder(workorder)
                                .workorderService(new WorkorderService(serviceId))
                                .technicianId(testTechnicianId)
                                .startTime(LocalDateTime.now(TEST_CLOCK).minusHours(3))
                                .endTime(LocalDateTime.now(TEST_CLOCK).minusHours(1))
                                .hoursWorked(BigDecimal.valueOf(2.0))
                                .notes("Test stopped session")
                                .createdBy(SYSTEM_USER_ID)
                                .createdAt(java.time.Instant.now(TEST_CLOCK))
                                .build();
                laborEntryRepository.save(entry);

                return workorderId;
        }

        /**
         * Seed a workorder with multiple labor entries for history testing.
         */
        private UUID seedWorkorderWithMultipleLaborEntries() {
                UUID workorderId = seedWorkInProgressWorkorder();
                UUID serviceId = workorderServiceRepository.findAll().get(0).getId();
                testTechnicianId = UUID.fromString("00000000-0000-0000-0000-000000000001");

                Workorder workorder = workorderRepository.findById(workorderId).orElseThrow();

                // Create first entry (older)
                WorkorderLaborEntry entry1 = WorkorderLaborEntry.builder()
                                .workorder(workorder)
                                .workorderService(new WorkorderService(serviceId))
                                .technicianId(testTechnicianId)
                                .startTime(LocalDateTime.now(TEST_CLOCK).minusDays(2))
                                .endTime(LocalDateTime.now(TEST_CLOCK).minusDays(2).plusHours(2))
                                .hoursWorked(BigDecimal.valueOf(2.0))
                                .notes("First session")
                                .createdBy(SYSTEM_USER_ID)
                                .createdAt(java.time.Instant.now(TEST_CLOCK))
                                .build();
                laborEntryRepository.save(entry1);

                // Create second entry (newer)
                WorkorderLaborEntry entry2 = WorkorderLaborEntry.builder()
                                .workorder(workorder)
                                .workorderService(new WorkorderService(serviceId))
                                .technicianId(testTechnicianId)
                                .startTime(LocalDateTime.now(TEST_CLOCK).minusDays(1))
                                .endTime(LocalDateTime.now(TEST_CLOCK).minusDays(1).plusHours(3))
                                .hoursWorked(BigDecimal.valueOf(3.0))
                                .notes("Second session")
                                .createdBy(SYSTEM_USER_ID)
                                .createdAt(java.time.Instant.now(TEST_CLOCK))
                                .build();
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
