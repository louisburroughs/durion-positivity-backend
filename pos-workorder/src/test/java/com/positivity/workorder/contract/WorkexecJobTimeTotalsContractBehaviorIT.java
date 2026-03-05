package com.positivity.workorder.contract;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import com.positivity.workorder.config.TestSecurityConfig;
import com.positivity.workorder.internal.entity.Workorder;
import com.positivity.workorder.internal.entity.WorkorderLaborEntry;
import com.positivity.workorder.internal.enums.WorkorderStatus;

@DisplayName("CAP-121 Workexec Job Time Totals Contract Behavior Tests")
@SpringBootTest
@ActiveProfiles("test")
@Import({ ContractTestConfiguration.class, TestSecurityConfig.class })
class WorkexecJobTimeTotalsContractBehaviorIT extends AbstractWorkexecContractBehaviorIT {
        private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);

        @Test
        @DisplayName("AC1: GET job-time-totals with valid params returns grouped totals by technicianId, locationId, localDate")
        void getJobTimeTotalsWithValidParamsReturnsGroupedTotals() throws Exception {
                UUID locationA = UUID.fromString("00000000-0000-0000-0000-000000000001");
                UUID locationB = UUID.fromString("00000000-0000-0000-0000-000000000001");
                UUID technicianA = UUID.fromString("00000000-0000-0000-0000-000000000001");
                UUID technicianB = UUID.fromString("00000000-0000-0000-0000-000000000001");

                Workorder completedAtLocationA = seedWorkorder(locationA, WorkorderStatus.COMPLETED);
                Workorder completedAtLocationB = seedWorkorder(locationB, WorkorderStatus.COMPLETED);

                seedLaborEntry(completedAtLocationA, technicianA,
                                LocalDateTime.of(2026, 2, 14, 8, 0),
                                LocalDateTime.of(2026, 2, 14, 9, 0),
                                BigDecimal.valueOf(1.00));
                seedLaborEntry(completedAtLocationA, technicianA,
                                LocalDateTime.of(2026, 2, 14, 10, 0),
                                LocalDateTime.of(2026, 2, 14, 10, 30),
                                BigDecimal.valueOf(0.50));
                seedLaborEntry(completedAtLocationB, technicianB,
                                LocalDateTime.of(2026, 2, 14, 12, 0),
                                LocalDateTime.of(2026, 2, 14, 13, 0),
                                BigDecimal.valueOf(1.00));

                String response = mockMvc.perform(get("/v1/workexec/job-time-totals")
                                .param("startDate", "2026-02-14")
                                .param("endDate", "2026-02-14")
                                .param("timezone", "UTC"))
                                .andExpect(status().isOk())
                                .andReturn()
                                .getResponse()
                                .getContentAsString();

                var json = objectMapper.readTree(response);
                assertThat(json).hasSize(2);

                assertThat(json.findValuesAsText("technicianId"))
                                .containsExactlyInAnyOrder(technicianA.toString(), technicianB.toString());
                assertThat(json.findValuesAsText("locationId"))
                                .containsExactlyInAnyOrder(locationA.toString(), locationB.toString());
                assertThat(json.findValuesAsText("localDate"))
                                .containsOnly("2026-02-14");
                assertThat(json.findValues("totalJobMinutes").stream().map(node -> node.asInt()).toList())
                                .containsExactlyInAnyOrder(90, 60);
        }

        @Test
        @DisplayName("AC2: GET job-time-totals missing required startDate returns 400")
        void getJobTimeTotalsMissingStartDateReturnsBadRequest() throws Exception {
                mockMvc.perform(get("/v1/workexec/job-time-totals")
                                .param("endDate", "2026-02-14")
                                .param("timezone", "UTC"))
                                .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("AC3: GET job-time-totals includes only finalized/completed labor and excludes in-progress work")
        void getJobTimeTotalsIncludesOnlyCompletedWorkorders() throws Exception {
                UUID sharedLocation = UUID.fromString("00000000-0000-0000-0000-000000000001");
                UUID sharedTechnician = UUID.fromString("00000000-0000-0000-0000-000000000001");

                Workorder completedWorkorder = seedWorkorder(sharedLocation, WorkorderStatus.COMPLETED);
                Workorder inProgressWorkorder = seedWorkorder(sharedLocation, WorkorderStatus.WORK_IN_PROGRESS);

                seedLaborEntry(completedWorkorder, sharedTechnician,
                                LocalDateTime.of(2026, 2, 15, 8, 0),
                                LocalDateTime.of(2026, 2, 15, 9, 0),
                                BigDecimal.valueOf(1.00));
                seedLaborEntry(inProgressWorkorder, sharedTechnician,
                                LocalDateTime.of(2026, 2, 15, 9, 0),
                                LocalDateTime.of(2026, 2, 15, 10, 0),
                                BigDecimal.valueOf(1.00));

                String response = mockMvc.perform(get("/v1/workexec/job-time-totals")
                                .param("startDate", "2026-02-15")
                                .param("endDate", "2026-02-15")
                                .param("timezone", "UTC")
                                .param("locationId", sharedLocation.toString())
                                .param("technicianIds", sharedTechnician.toString()))
                                .andExpect(status().isOk())
                                .andReturn()
                                .getResponse()
                                .getContentAsString();

                var json = objectMapper.readTree(response);
                assertThat(json).hasSize(1);
                assertThat(json.get(0).path("technicianId").asText()).isEqualTo(sharedTechnician.toString());
                assertThat(json.get(0).path("locationId").asText()).isEqualTo(sharedLocation.toString());
                assertThat(LocalDate.parse(json.get(0).path("localDate").asText()))
                                .isEqualTo(LocalDate.of(2026, 2, 15));
                assertThat(json.get(0).path("totalJobMinutes").asInt()).isEqualTo(60);
        }

        private WorkorderLaborEntry seedLaborEntry(
                        Workorder workorder,
                        UUID technicianId,
                        LocalDateTime startTimeUtc,
                        LocalDateTime endTimeUtc,
                        BigDecimal hoursWorked) {
                WorkorderLaborEntry entry = WorkorderLaborEntry.builder()
                                .workorder(workorder)
                                .workorderId(workorder.getId())
                                .workorderServiceId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                                .technicianId(technicianId)
                                .startTime(startTimeUtc)
                                .endTime(endTimeUtc)
                                .hoursWorked(hoursWorked)
                                .notes("contract-test")
                                .createdBy("system")
                                .createdAt(Instant.now(TEST_CLOCK).atOffset(ZoneOffset.UTC).toInstant())
                                .build();
                return laborEntryRepository.save(entry);
        }
}
