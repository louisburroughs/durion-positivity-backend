package com.positivity.inventory.internal.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.inventory.config.TestSecurityConfig;
import com.positivity.inventory.internal.dto.cyclecount.schedule.CycleCountScheduleResponse;
import com.positivity.inventory.service.CycleCountScheduleService;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web-slice tests for CycleCountScheduleController (odoo-parity I1, #1031):
 * permission gating and request/response mapping.
 */
@WebMvcTest(CycleCountScheduleController.class)
@Import(TestSecurityConfig.class)
@ActiveProfiles("test")
@SuppressWarnings({"java:S6813", "java:S100", "java:S1192"})
class CycleCountScheduleControllerTest {

    private static final UUID SCHEDULE_ID = UUID.fromString("00000000-0000-0000-0000-000000000050");
    private static final UUID LOCATION_ID = UUID.fromString("00000000-0000-0000-0000-000000000051");

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    java.time.Clock clock;

    @MockitoBean
    CycleCountScheduleService cycleCountScheduleService;

    private CycleCountScheduleResponse sampleResponse() {
        return CycleCountScheduleResponse.builder()
                .scheduleId(SCHEDULE_ID)
                .locationId(LOCATION_ID)
                .frequencyDays(30)
                .nextDueDate(LocalDate.parse("2026-08-01"))
                .autoCreatePlan(true)
                .active(true)
                .createdBy("test-user")
                .createdAt(Instant.parse("2026-07-01T00:00:00Z"))
                .updatedAt(Instant.parse("2026-07-01T00:00:00Z"))
                .build();
    }

    // ─── POST /v1/inventory/cycleCountSchedules ──────────────────────────────

    @Test
    void createSchedule_withInitiateAuthority_returns201() throws Exception {
        when(cycleCountScheduleService.createSchedule(any(), eq("test-user"))).thenReturn(sampleResponse());

        mockMvc.perform(post("/v1/inventory/cycleCountSchedules")
                        .header("X-Authorities", "inventory:cycle_count:initiate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"locationId":"%s","frequencyDays":30,"nextDueDate":"2026-08-01","autoCreatePlan":true}
                                """.formatted(LOCATION_ID)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.scheduleId").value(SCHEDULE_ID.toString()))
                .andExpect(jsonPath("$.active").value(true));
    }

    @Test
    void createSchedule_missingInitiateAuthority_returns403() throws Exception {
        mockMvc.perform(post("/v1/inventory/cycleCountSchedules")
                        .header("X-Authorities", "inventory:cycle_count:view")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"locationId":"%s","frequencyDays":30,"nextDueDate":"2026-08-01"}
                                """.formatted(LOCATION_ID)))
                .andExpect(status().isForbidden());
    }

    @Test
    void createSchedule_missingRequiredFields_returns400() throws Exception {
        mockMvc.perform(post("/v1/inventory/cycleCountSchedules")
                        .header("X-Authorities", "inventory:cycle_count:initiate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    // ─── GET /v1/inventory/cycleCountSchedules ───────────────────────────────

    @Test
    void listSchedules_dueView_passesFiltersThrough() throws Exception {
        when(cycleCountScheduleService.listSchedules(isNull(), isNull(), eq(true), eq(0), eq(50)))
                .thenReturn(List.of(sampleResponse()));

        mockMvc.perform(get("/v1/inventory/cycleCountSchedules")
                        .header("X-Authorities", "inventory:cycle_count:view")
                        .param("due", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].scheduleId").value(SCHEDULE_ID.toString()));

        verify(cycleCountScheduleService).listSchedules(null, null, true, 0, 50);
    }

    @Test
    void listSchedules_missingViewAuthority_returns403() throws Exception {
        mockMvc.perform(get("/v1/inventory/cycleCountSchedules")
                        .header("X-Authorities", "inventory:cycle_count:initiate"))
                .andExpect(status().isForbidden());
    }

    // ─── PUT /v1/inventory/cycleCountSchedules/{id} ──────────────────────────

    @Test
    void updateSchedule_withInitiateAuthority_returns200() throws Exception {
        when(cycleCountScheduleService.updateSchedule(eq(SCHEDULE_ID), any())).thenReturn(sampleResponse());

        mockMvc.perform(put("/v1/inventory/cycleCountSchedules/{id}", SCHEDULE_ID)
                        .header("X-Authorities", "inventory:cycle_count:initiate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"frequencyDays\":14}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scheduleId").value(SCHEDULE_ID.toString()));
    }

    @Test
    void updateSchedule_missingInitiateAuthority_returns403() throws Exception {
        mockMvc.perform(put("/v1/inventory/cycleCountSchedules/{id}", SCHEDULE_ID)
                        .header("X-Authorities", "inventory:cycle_count:view")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"frequencyDays\":14}"))
                .andExpect(status().isForbidden());
    }

    // ─── DELETE /v1/inventory/cycleCountSchedules/{id} ───────────────────────

    @Test
    void deactivateSchedule_withInitiateAuthority_returns200() throws Exception {
        CycleCountScheduleResponse deactivated = sampleResponse();
        deactivated.setActive(false);
        when(cycleCountScheduleService.deactivateSchedule(SCHEDULE_ID)).thenReturn(deactivated);

        mockMvc.perform(delete("/v1/inventory/cycleCountSchedules/{id}", SCHEDULE_ID)
                        .header("X-Authorities", "inventory:cycle_count:initiate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));

        verify(cycleCountScheduleService).deactivateSchedule(SCHEDULE_ID);
    }

    @Test
    void deactivateSchedule_missingInitiateAuthority_returns403() throws Exception {
        mockMvc.perform(delete("/v1/inventory/cycleCountSchedules/{id}", SCHEDULE_ID)
                        .header("X-Authorities", "inventory:cycle_count:view"))
                .andExpect(status().isForbidden());
    }
}
