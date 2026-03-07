package com.positivity.workorder.contract;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;

import com.positivity.workorder.config.TestSecurityConfig;
import com.positivity.workorder.internal.entity.Workorder;

@DisplayName("CAP-121 Workexec Timer Contract Behavior Tests")
@SpringBootTest
@ActiveProfiles("test")
@Import({ ContractTestConfiguration.class, TestSecurityConfig.class })
class WorkexecTimerContractBehaviorIT extends AbstractWorkexecContractBehaviorIT {

        @Test
        @DisplayName("AC1: start then stop transitions timer from ACTIVE to COMPLETED")
        void startThenStopTransitionsActiveToCompleted() throws Exception {
                Workorder workorder = seedWorkorderInProgress();
                UUID mechanicId = UUID.fromString("00000000-0000-0000-0000-000000000001");

                Map<String, Object> startPayload = WorkexecContractPayloads.timerStartPayload(workorder.getId(),
                                "DIAG");

                mockMvc.perform(post("/v1/workexec/time-entries/timer/start")
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("X-User-Id", mechanicId.toString())
                                .content(objectMapper.writeValueAsString(startPayload)))
                                .andExpect(status().isCreated())
                                .andExpect(jsonPath("$.status").value("ACTIVE"))
                                .andExpect(jsonPath("$.mechanicId").value(mechanicId.toString()))
                                .andExpect(jsonPath("$.workorderId").value(workorder.getId().toString()));

                mockMvc.perform(get("/v1/workexec/time-entries/timer/active")
                                .header("X-User-Id", mechanicId.toString()))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$[0].status").value("ACTIVE"));

                mockMvc.perform(post("/v1/workexec/time-entries/timer/stop")
                                .header("X-User-Id", mechanicId.toString()))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.stopped[0].status").value("COMPLETED"));

                mockMvc.perform(get("/v1/workexec/time-entries/timer/active")
                                .header("X-User-Id", mechanicId.toString()))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$[*]").isEmpty());
        }

        @Test
        @DisplayName("AC2: second timer start for same mechanic returns 409 TIMER_ALREADY_ACTIVE")
        void secondStartReturnsTimerAlreadyActive() throws Exception {
                Workorder workorder = seedWorkorderInProgress();
                UUID mechanicId = UUID.fromString("00000000-0000-0000-0000-000000000001");

                Map<String, Object> startPayload = WorkexecContractPayloads.timerStartPayload(workorder.getId());

                mockMvc.perform(post("/v1/workexec/time-entries/timer/start")
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("X-User-Id", mechanicId.toString())
                                .content(objectMapper.writeValueAsString(startPayload)))
                                .andExpect(status().isCreated())
                                .andExpect(jsonPath("$.status").value("ACTIVE"));

                mockMvc.perform(post("/v1/workexec/time-entries/timer/start")
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("X-User-Id", mechanicId.toString())
                                .content(objectMapper.writeValueAsString(startPayload)))
                                .andExpect(status().isConflict())
                                .andExpect(jsonPath("$.code").value("TIMER_ALREADY_ACTIVE"));
        }

        @Test
        @DisplayName("AC3: stop with no active timer returns 409 NO_ACTIVE_TIMER")
        void stopWithoutActiveTimerReturnsConflict() throws Exception {
                UUID mechanicId = UUID.fromString("00000000-0000-0000-0000-000000000001");

                mockMvc.perform(post("/v1/workexec/time-entries/timer/stop")
                                .header("X-User-Id", mechanicId.toString()))
                                .andExpect(status().isConflict())
                                .andExpect(jsonPath("$.code").value("NO_ACTIVE_TIMER"));
        }
}
