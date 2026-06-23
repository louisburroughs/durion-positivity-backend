package com.positivity.workorder.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.workorder.config.TestSecurityConfig;
import com.positivity.workorder.internal.entity.Workorder;
import com.positivity.workorder.internal.entity.WorkorderLaborEntry;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;

@DisplayName("CAP-121 Workexec Timer Contract Behavior Tests")
@SpringBootTest
@ActiveProfiles("test")
@Import({ContractTestConfiguration.class, TestSecurityConfig.class})
class WorkexecTimerContractBehaviorIT extends AbstractWorkexecContractBehaviorIT {

    @Test
    @DisplayName("AC1: start then stop transitions timer from ACTIVE to COMPLETED")
    void startThenStopTransitionsActiveToCompleted() throws Exception {
        Workorder workorder = seedWorkorderInProgress();
        UUID mechanicId = UUID.fromString("00000000-0000-0000-0000-000000000001");

        Map<String, Object> startPayload = WorkexecContractPayloads.timerStartPayload(workorder.getId(), "DIAG");

        mockMvc.perform(post("/v1/workexec/time-entries/timer/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-User-Id", mechanicId.toString())
                        .content(objectMapper.writeValueAsString(startPayload)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.mechanicId").value(mechanicId.toString()))
                .andExpect(jsonPath("$.workorderId").value(workorder.getId().toString()));

        mockMvc.perform(get("/v1/workexec/time-entries/timer/active").header("X-User-Id", mechanicId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("ACTIVE"));

        mockMvc.perform(post("/v1/workexec/time-entries/timer/stop").header("X-User-Id", mechanicId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stopped[0].status").value("COMPLETED"));

        mockMvc.perform(get("/v1/workexec/time-entries/timer/active").header("X-User-Id", mechanicId.toString()))
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
    @DisplayName("AC4: unassigned actor starting timer attributes labor to the assigned technician")
    void unassignedActorAttributesToAssignedTechnician() throws Exception {
        Workorder workorder = seedWorkorderInProgress();
        UUID assignedTech = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
        UUID actor = UUID.fromString("00000000-0000-0000-0000-0000000000bb");
        seedCurrentAssignment(workorder, assignedTech);

        // Actor (not the assigned tech) starts a timer with no explicit technician.
        // Base authority suffices because the tracked technician defaults to the current assignment.
        mockMvc.perform(post("/v1/workexec/time-entries/timer/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-User-Id", actor.toString())
                        .content(objectMapper.writeValueAsString(
                                WorkexecContractPayloads.timerStartPayload(workorder.getId()))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.mechanicId").value(assignedTech.toString()));
    }

    @Test
    @DisplayName("#729: a default-attribution timer can be stopped by its initiator")
    void defaultAttributionTimerCanBeStoppedByInitiator() throws Exception {
        Workorder workorder = seedWorkorderInProgress();
        UUID assignedTech = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
        UUID actor = UUID.fromString("00000000-0000-0000-0000-0000000000bb");
        seedCurrentAssignment(workorder, assignedTech);

        // Actor (not the assigned tech) starts a timer; labor is tracked under the assigned technician.
        mockMvc.perform(post("/v1/workexec/time-entries/timer/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-User-Id", actor.toString())
                        .content(objectMapper.writeValueAsString(
                                WorkexecContractPayloads.timerStartPayload(workorder.getId()))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.mechanicId").value(assignedTech.toString()));

        // The initiator's active list surfaces the timer they started for the assigned technician.
        mockMvc.perform(get("/v1/workexec/time-entries/timer/active").header("X-User-Id", actor.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$[0].mechanicId").value(assignedTech.toString()));

        // Regression: the initiator can stop it (previously 409 NO_ACTIVE_TIMER, leaving it open forever).
        mockMvc.perform(post("/v1/workexec/time-entries/timer/stop").header("X-User-Id", actor.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stopped[0].status").value("COMPLETED"))
                .andExpect(jsonPath("$.stopped[0].mechanicId").value(assignedTech.toString()));

        // The assigned technician's labor entry is now closed.
        List<WorkorderLaborEntry> openForTech =
                laborEntryRepository.findByTechnicianIdAndEndTimeIsNullOrderByStartTimeDesc(assignedTech);
        assertThat(openForTech).isEmpty();
    }

    @Test
    @DisplayName("AC5: on-behalf start for a third technician without authority returns 403")
    void onBehalfWithoutAuthorityForbidden() throws Exception {
        Workorder workorder = seedWorkorderInProgress();
        UUID assignedTech = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
        UUID actor = UUID.fromString("00000000-0000-0000-0000-0000000000bb");
        UUID otherTech = UUID.fromString("00000000-0000-0000-0000-0000000000cc");
        seedCurrentAssignment(workorder, assignedTech);

        mockMvc.perform(post("/v1/workexec/time-entries/timer/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-User-Id", actor.toString())
                        .content(objectMapper.writeValueAsString(WorkexecContractPayloads.timerStartOnBehalfPayload(
                                workorder.getId(), otherTech, "covering shift"))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("AC6: on-behalf start with authority but no reason returns 400")
    void onBehalfWithAuthorityMissingReasonBadRequest() throws Exception {
        Workorder workorder = seedWorkorderInProgress();
        UUID assignedTech = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
        UUID otherTech = UUID.fromString("00000000-0000-0000-0000-0000000000cc");
        seedCurrentAssignment(workorder, assignedTech);
        grantAuthority("workorder:labor:add_on_behalf");

        mockMvc.perform(post("/v1/workexec/time-entries/timer/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-User-Id", "00000000-0000-0000-0000-0000000000bb")
                        .content(objectMapper.writeValueAsString(WorkexecContractPayloads.timerStartOnBehalfPayload(
                                workorder.getId(), otherTech, null))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    @Test
    @DisplayName("AC7: on-behalf start with authority and reason attributes labor to the named technician")
    void onBehalfWithAuthorityAndReasonSucceeds() throws Exception {
        Workorder workorder = seedWorkorderInProgress();
        UUID assignedTech = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
        UUID otherTech = UUID.fromString("00000000-0000-0000-0000-0000000000cc");
        seedCurrentAssignment(workorder, assignedTech);
        grantAuthority("workorder:labor:add_on_behalf");

        mockMvc.perform(post("/v1/workexec/time-entries/timer/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-User-Id", "00000000-0000-0000-0000-0000000000bb")
                        .content(objectMapper.writeValueAsString(WorkexecContractPayloads.timerStartOnBehalfPayload(
                                workorder.getId(), otherTech, "lead covering for helper"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.mechanicId").value(otherTech.toString()));

        // The on-behalf attribution is durably audited: reason + initiating actor are persisted.
        List<WorkorderLaborEntry> entries =
                laborEntryRepository.findByTechnicianIdAndEndTimeIsNullOrderByStartTimeDesc(otherTech);
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getOnBehalfReason()).isEqualTo("lead covering for helper");
        assertThat(entries.get(0).getActedByUserId())
                .isEqualTo(UUID.fromString("00000000-0000-0000-0000-0000000000bb"));
    }

    @Test
    @DisplayName("AC8: actor starting own timer on a workorder assigned to another tech uses the base path")
    void selfStartWhileDifferentTechAssignedUsesBasePath() throws Exception {
        Workorder workorder = seedWorkorderInProgress();
        UUID assignedTech = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
        UUID actor = UUID.fromString("00000000-0000-0000-0000-0000000000bb");
        seedCurrentAssignment(workorder, assignedTech);

        // Actor attributes labor to themselves (helper logging own work) — allowed on base authority,
        // not treated as on-behalf, even though a different technician is assigned.
        mockMvc.perform(post("/v1/workexec/time-entries/timer/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-User-Id", actor.toString())
                        .content(objectMapper.writeValueAsString(
                                WorkexecContractPayloads.timerStartOnBehalfPayload(workorder.getId(), actor, null))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.mechanicId").value(actor.toString()));

        List<WorkorderLaborEntry> entries =
                laborEntryRepository.findByTechnicianIdAndEndTimeIsNullOrderByStartTimeDesc(actor);
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getOnBehalfReason()).isNull();
        assertThat(entries.get(0).getActedByUserId()).isNull();
    }

    @Test
    @DisplayName("AC3: stop with no active timer returns 409 NO_ACTIVE_TIMER")
    void stopWithoutActiveTimerReturnsConflict() throws Exception {
        UUID mechanicId = UUID.fromString("00000000-0000-0000-0000-000000000001");

        mockMvc.perform(post("/v1/workexec/time-entries/timer/stop").header("X-User-Id", mechanicId.toString()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NO_ACTIVE_TIMER"));
    }
}
