package com.positivity.workorder.internal.controller;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.workorder.config.TestSecurityConfig;
import com.positivity.workorder.internal.dto.OpenWorkordersByCustomerResponse;
import com.positivity.workorder.internal.dto.ReopenedWorkorderAnalyticsResponse;
import com.positivity.workorder.internal.dto.TechnicianLaborAnalyticsResponse;
import com.positivity.workorder.internal.dto.WorkorderStatusTransitionsResponse;
import com.positivity.workorder.internal.enums.WorkorderStatus;
import com.positivity.workorder.internal.exception.WorkorderRequestValidationException;
import com.positivity.workorder.internal.service.WorkorderAnalyticsService;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Controller-layer tests for the Wave 2 analytics endpoints (#1593/#1594/#1595): routing,
 * permission enforcement, and delegation to {@link WorkorderAnalyticsService}. Business logic
 * (validation, computation) is covered by {@code WorkorderAnalyticsServiceImplTest}.
 */
@WebMvcTest(WorkorderAnalyticsController.class)
@Import(TestSecurityConfig.class)
@ActiveProfiles("test")
class WorkorderAnalyticsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private WorkorderAnalyticsService analyticsService;

    @Test
    @DisplayName("GET /status-transitions?woId=... delegates to the service and returns 200")
    void statusTransitions_woIdMode_returns200() throws Exception {
        UUID woId = UUID.randomUUID();
        when(analyticsService.getStatusTransitions(eq(woId), isNull(), isNull(), isNull(), isNull(), eq(100)))
                .thenReturn(WorkorderStatusTransitionsResponse.builder()
                        .transitions(List.of())
                        .truncated(false)
                        .limit(100)
                        .build());

        mockMvc.perform(get("/v1/workorders/status-transitions").param("woId", woId.toString()))
                .andExpect(status().isOk());

        verify(analyticsService).getStatusTransitions(eq(woId), isNull(), isNull(), isNull(), isNull(), eq(100));
    }

    @Test
    @DisplayName("GET /status-transitions with neither woId nor range params surfaces the service's 400")
    void statusTransitions_emptyCombination_returns400() throws Exception {
        when(analyticsService.getStatusTransitions(isNull(), isNull(), isNull(), isNull(), isNull(), anyInt()))
                .thenThrow(new WorkorderRequestValidationException(
                        "Supply either woId alone, or startDate and endDate (optionally narrowed by from/to)"));

        mockMvc.perform(get("/v1/workorders/status-transitions"))
                .andExpect(status().isBadRequest())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code")
                        .value("INVALID_ARGUMENT"));
    }

    @Test
    @DisplayName("GET /status-transitions without workorder:analytics:view is forbidden")
    void statusTransitions_missingPermission_returns403() throws Exception {
        mockMvc.perform(get("/v1/workorders/status-transitions")
                        .param("woId", UUID.randomUUID().toString())
                        .header("X-Authorities", "workorder:workorder:view"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /analytics/reopened delegates startDate/endDate/withinDays/limit and returns 200")
    void reopenedAnalytics_returns200() throws Exception {
        when(analyticsService.getReopenedWorkorders(
                        eq(LocalDate.of(2026, 6, 1)), eq(LocalDate.of(2026, 6, 30)), eq(7), eq(100)))
                .thenReturn(ReopenedWorkorderAnalyticsResponse.builder()
                        .rows(List.of())
                        .truncated(false)
                        .limit(100)
                        .build());

        mockMvc.perform(get("/v1/workorders/analytics/reopened")
                        .param("startDate", "2026-06-01")
                        .param("endDate", "2026-06-30"))
                .andExpect(status().isOk());

        verify(analyticsService)
                .getReopenedWorkorders(eq(LocalDate.of(2026, 6, 1)), eq(LocalDate.of(2026, 6, 30)), eq(7), eq(100));
    }

    @Test
    @DisplayName("GET /analytics/reopened rejects withinDays above the documented maximum (90) with a 400 ApiError")
    void reopenedAnalytics_withinDaysAboveMax_returns400() throws Exception {
        mockMvc.perform(get("/v1/workorders/analytics/reopened")
                        .param("startDate", "2026-06-01")
                        .param("endDate", "2026-06-30")
                        .param("withinDays", "91"))
                .andExpect(status().isBadRequest())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code")
                        .value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("GET /analytics/technician-labor delegates and returns 200")
    void technicianLaborAnalytics_returns200() throws Exception {
        when(analyticsService.getTechnicianLabor(eq(LocalDate.of(2026, 6, 1)), eq(LocalDate.of(2026, 6, 30)), eq(100)))
                .thenReturn(TechnicianLaborAnalyticsResponse.builder()
                        .rows(List.of())
                        .truncated(false)
                        .limit(100)
                        .build());

        mockMvc.perform(get("/v1/workorders/analytics/technician-labor")
                        .param("startDate", "2026-06-01")
                        .param("endDate", "2026-06-30"))
                .andExpect(status().isOk());

        verify(analyticsService)
                .getTechnicianLabor(eq(LocalDate.of(2026, 6, 1)), eq(LocalDate.of(2026, 6, 30)), eq(100));
    }

    @Test
    @DisplayName("GET /status-transitions?from=&to= (range mode) forwards the enum filters")
    void statusTransitions_rangeModeWithFromTo_forwardsFilters() throws Exception {
        when(analyticsService.getStatusTransitions(
                        isNull(),
                        eq(WorkorderStatus.WORK_IN_PROGRESS),
                        eq(WorkorderStatus.COMPLETED),
                        eq(LocalDate.of(2026, 6, 1)),
                        eq(LocalDate.of(2026, 6, 30)),
                        eq(50)))
                .thenReturn(WorkorderStatusTransitionsResponse.builder()
                        .transitions(List.of())
                        .truncated(false)
                        .limit(50)
                        .build());

        mockMvc.perform(get("/v1/workorders/status-transitions")
                        .param("from", "WORK_IN_PROGRESS")
                        .param("to", "COMPLETED")
                        .param("startDate", "2026-06-01")
                        .param("endDate", "2026-06-30")
                        .param("limit", "50"))
                .andExpect(status().isOk());

        verify(analyticsService)
                .getStatusTransitions(
                        isNull(),
                        eq(WorkorderStatus.WORK_IN_PROGRESS),
                        eq(WorkorderStatus.COMPLETED),
                        eq(LocalDate.of(2026, 6, 1)),
                        eq(LocalDate.of(2026, 6, 30)),
                        eq(50));
    }

    @Test
    @DisplayName("GET /analytics/open-by-customer delegates with the default limit and returns 200 (#1855)")
    void openByCustomer_defaultLimit_returns200() throws Exception {
        when(analyticsService.getOpenWorkordersByCustomer(eq(100)))
                .thenReturn(OpenWorkordersByCustomerResponse.builder()
                        .rows(List.of())
                        .totalCustomers(0)
                        .totalOpenWorkorders(0L)
                        .truncated(false)
                        .limit(100)
                        .build());

        mockMvc.perform(get("/v1/workorders/analytics/open-by-customer")).andExpect(status().isOk());

        verify(analyticsService).getOpenWorkordersByCustomer(100);
    }

    @Test
    @DisplayName("an over-large limit is clamped to the cap rather than rejected")
    void openByCustomer_clampsLimit() throws Exception {
        when(analyticsService.getOpenWorkordersByCustomer(eq(500)))
                .thenReturn(OpenWorkordersByCustomerResponse.builder()
                        .rows(List.of())
                        .totalCustomers(0)
                        .totalOpenWorkorders(0L)
                        .truncated(false)
                        .limit(500)
                        .build());

        mockMvc.perform(get("/v1/workorders/analytics/open-by-customer").param("limit", "5000"))
                .andExpect(status().isOk());

        verify(analyticsService).getOpenWorkordersByCustomer(500);
    }
}
