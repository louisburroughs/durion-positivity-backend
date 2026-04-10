package com.positivity.workorder.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import com.positivity.workorder.internal.entity.Workorder;
import com.positivity.workorder.internal.entity.WorkorderLaborEntry;
import com.positivity.workorder.internal.entity.WorkorderServiceLine;
import com.positivity.workorder.internal.enums.WorkorderStatus;
import com.positivity.workorder.internal.repository.WorkorderLaborEntryRepository;
import com.positivity.workorder.internal.repository.WorkorderRepository;
import com.positivity.workorder.internal.repository.WorkorderServiceRepository;
import com.positivity.workorder.support.BaseContractIntegrationTest;

import io.restassured.http.ContentType;

@DisplayName("CAP-121 Workexec Time Tracking Contract Behavior Tests")
@Import(ContractTestConfiguration.class)
class WorkexecTimeTrackingContractBehaviorIT extends BaseContractIntegrationTest {

        @Autowired
        private WorkorderRepository workorderRepository;

        @Autowired
        private WorkorderLaborEntryRepository laborEntryRepository;

        @Autowired
        private WorkorderServiceRepository workorderServiceRepository;

        @AfterEach
        void tearDown() {
                purgeTestData();
        }

        @Test
        @DisplayName("CAP121-81: labor-performed create returns 201 and replay returns 200 with replay header")
        void laborPerformedCreateAndReplay() {
                Workorder workorder = seedWorkorder(WorkorderStatus.WORK_IN_PROGRESS,
                                UUID.fromString("00000000-0000-0000-0000-000000000001"));
                UUID technicianId = UUID.fromString("00000000-0000-0000-0000-000000000001");
                String idempotencyKey = "cap121-labor-" + UUID.fromString("00000000-0000-0000-0000-000000000001");

                Map<String, Object> payload = WorkexecContractPayloads.laborPerformedPayload(
                                workorder.getId(),
                                technicianId,
                                BigDecimal.valueOf(1.5),
                                "te-123");

                String firstId = givenWithGatewayAuth()
                                .contentType(ContentType.JSON)
                                .header("Idempotency-Key", idempotencyKey)
                                .body(payload)
                                .when()
                                .post("/v1/workexec/labor-performed")
                                .then()
                                .statusCode(201)
                                .body("laborPerformedId", notNullValue())
                                .body("workorderId", equalTo(workorder.getId().toString()))
                                .extract()
                                .path("laborPerformedId");

                givenWithGatewayAuth()
                                .contentType(ContentType.JSON)
                                .header("Idempotency-Key", idempotencyKey)
                                .body(payload)
                                .when()
                                .post("/v1/workexec/labor-performed")
                                .then()
                                .statusCode(200)
                                .header("Idempotency-Replayed", equalTo("true"))
                                .body("laborPerformedId", equalTo(firstId));
        }

        @Test
        @DisplayName("CAP121-81: labor-performed rejects blocked workorder status with stable conflict code")
        void laborPerformedRejectsBlockedWorkorderStatus() {
                Workorder workorder = seedWorkorder(WorkorderStatus.CANCELLED,
                                UUID.fromString("00000000-0000-0000-0000-000000000001"));

                Map<String, Object> payload = WorkexecContractPayloads.laborPerformedPayload(
                                workorder.getId(),
                                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                                BigDecimal.ONE,
                                "te-blocked");

                givenWithGatewayAuth()
                                .contentType(ContentType.JSON)
                                .header("Idempotency-Key",
                                                "cap121-blocked-" + UUID
                                                                .fromString("00000000-0000-0000-0000-000000000001"))
                                .body(payload)
                                .when()
                                .post("/v1/workexec/labor-performed")
                                .then()
                                .statusCode(409)
                                .body("code", equalTo("WORKEXEC_CONFLICT_WORKORDER_STATE"));
        }

        @Test
        @DisplayName("CAP121-82: timer start creates active entry and second start fails with TIMER_ALREADY_ACTIVE")
        void timerStartAndSingleActiveConstraint() {
                Workorder workorder = seedWorkorder(WorkorderStatus.WORK_IN_PROGRESS,
                                UUID.fromString("00000000-0000-0000-0000-000000000001"));

                Map<String, Object> startPayload = WorkexecContractPayloads.timerStartPayload(workorder.getId(),
                                "DIAG");

                givenWithGatewayAuth()
                                .contentType(ContentType.JSON)
                                .body(startPayload)
                                .when()
                                .post("/v1/workexec/time-entries/timer/start")
                                .then()
                                .statusCode(201)
                                .body("status", equalTo("ACTIVE"));

                givenWithGatewayAuth()
                                .when()
                                .request("GET", "/v1/workexec/time-entries/timer/active")
                                .then()
                                .statusCode(200)
                                .body("size()", equalTo(1));

                givenWithGatewayAuth()
                                .contentType(ContentType.JSON)
                                .body(startPayload)
                                .when()
                                .post("/v1/workexec/time-entries/timer/start")
                                .then()
                                .statusCode(409)
                                .body("code", equalTo("TIMER_ALREADY_ACTIVE"));
        }

        @Test
        @DisplayName("CAP121-82: timer stop fails when no active timer and succeeds after start")
        void timerStopBehavior() {
                Workorder workorder = seedWorkorder(WorkorderStatus.WORK_IN_PROGRESS,
                                UUID.fromString("00000000-0000-0000-0000-000000000001"));

                givenWithGatewayAuth()
                                .when()
                                .post("/v1/workexec/time-entries/timer/stop")
                                .then()
                                .statusCode(409)
                                .body("code", equalTo("NO_ACTIVE_TIMER"));

                givenWithGatewayAuth()
                                .contentType(ContentType.JSON)
                                .body(WorkexecContractPayloads.timerStartPayload(workorder.getId()))
                                .when()
                                .post("/v1/workexec/time-entries/timer/start")
                                .then()
                                .statusCode(201);

                givenWithGatewayAuth()
                                .when()
                                .post("/v1/workexec/time-entries/timer/stop")
                                .then()
                                .statusCode(200)
                                .body("stopped", hasSize(1))
                                .body("stopped[0].status", equalTo("COMPLETED"));
        }

        @Test
        @DisplayName("CAP121-80 dependency: job-time-totals aggregates by technician/location/date")
        void jobTimeTotalsAggregation() {
                UUID locationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
                UUID technicianId = UUID.fromString("00000000-0000-0000-0000-000000000001");
                Workorder workorder = seedWorkorder(WorkorderStatus.COMPLETED, locationId);

                seedCompletedLaborEntry(workorder, technicianId, LocalDateTime.of(2026, 2, 16, 10, 0),
                                new BigDecimal("1.50"));
                seedCompletedLaborEntry(workorder, technicianId, LocalDateTime.of(2026, 2, 16, 12, 0),
                                new BigDecimal("0.50"));

                String date = LocalDate.of(2026, 2, 16).format(DateTimeFormatter.ISO_LOCAL_DATE);

                givenWithGatewayAuth()
                                .queryParam("startDate", date)
                                .queryParam("endDate", date)
                                .queryParam("timezone", "UTC")
                                .queryParam("locationId", locationId.toString())
                                .queryParam("technicianIds", technicianId.toString())
                                .when()
                                .request("GET", "/v1/workexec/job-time-totals")
                                .then()
                                .statusCode(200)
                                .body("size()", equalTo(1))
                                .body("[0].technicianId", equalTo(technicianId.toString()))
                                .body("[0].locationId", equalTo(locationId.toString()))
                                .body("[0].localDate", equalTo(date))
                                .body("[0].totalJobMinutes", equalTo(120));

                assertThat(laborEntryRepository.findByWorkorder_IdOrderByStartTimeDesc(workorder.getId())).hasSize(2);
        }

        private Workorder seedWorkorder(WorkorderStatus status, UUID shopId) {
                Workorder workorder = Workorder.builder()
                                .shopId(shopId)
                                .customerId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                                .vehicleId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                                .status(status)
                                .isReopened(false)
                                .build();
                return workorderRepository.save(workorder);
        }

        private void seedCompletedLaborEntry(
                        Workorder workorder,
                        UUID technicianId,
                        LocalDateTime endTimeUtc,
                        BigDecimal hoursWorked) {
                long seconds = hoursWorked.multiply(BigDecimal.valueOf(3600)).longValue();
                var service = workorderServiceRepository.findByWorkOrder_Id(workorder.getId()).stream()
                                .findFirst()
                                .orElseGet(() -> seedWorkorderService(workorder, technicianId));
                WorkorderLaborEntry entry = WorkorderLaborEntry.builder()
                                .workorder(workorder)
                                .workorderService(service)
                                .technicianId(technicianId)
                                .startTime(endTimeUtc.minusSeconds(seconds))
                                .endTime(endTimeUtc)
                                .hoursWorked(hoursWorked)
                                .createdBy(SYSTEM_USER_ID)
                                .createdAt(endTimeUtc.toInstant(ZoneOffset.UTC))
                                .build();
                laborEntryRepository.save(entry);
        }

        private WorkorderServiceLine seedWorkorderService(Workorder workorder, UUID technicianId) {
                WorkorderServiceLine service = WorkorderServiceLine.builder()
                                .workOrder(workorder)
                                .serviceEntityId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                                .technicianId(technicianId)
                                .description("Contract labor")
                                .build();
                return workorderServiceRepository.save(service);
        }
}
