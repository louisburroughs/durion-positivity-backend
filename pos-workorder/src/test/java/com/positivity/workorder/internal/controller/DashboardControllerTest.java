package com.positivity.workorder.internal.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.workorder.config.TestSecurityConfig;
import com.positivity.workorder.internal.dto.DashboardResponse;
import com.positivity.workorder.service.DashboardService;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Controller-layer tests for CAP-142 Story #60: Daily Dispatch Board Dashboard
 * endpoints.
 *
 * <p>
 * Verifies HTTP routing, required parameter enforcement, date parameter
 * forwarding,
 * and default-date (today) behaviour for {@link DashboardController}.
 *
 * Issue: CAP-142
 */
@WebMvcTest(DashboardController.class)
@Import(TestSecurityConfig.class)
@ActiveProfiles("test")
class DashboardControllerTest {

    private static final String DASHBOARD_URL = "/v1/workexec/dashboard/today";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    @SuppressWarnings("unused")
    private ObjectMapper objectMapper;

    @MockitoBean
    private DashboardService dashboardService;

    // -----------------------------------------------------------------------
    // AC-1 (controller): GET with locationId returns 200 OK with DashboardResponse
    // body
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("AC-1: GET /today?locationId=LOC-123 returns 200 OK with non-null response body")
    void getDashboard_withLocationId_returns200() throws Exception {
        // Arrange
        // Issue CAP-142: AC-1 — happy path, mock service returns structured response
        DashboardResponse expectedResponse = DashboardResponse.builder()
                .locationId("LOC-123")
                .date(LocalDate.now())
                .workorders(List.of())
                .mechanics(List.of())
                .bays(List.of())
                .conflicts(List.of())
                .lastRefreshed(Instant.now())
                .build();
        when(dashboardService.getDashboard(eq("LOC-123"), any(LocalDate.class)))
                .thenReturn(expectedResponse);

        // Act + Assert
        mockMvc.perform(get(DASHBOARD_URL).param("locationId", "LOC-123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.locationId").value("LOC-123"))
                .andExpect(jsonPath("$.lastRefreshed").exists());
    }

    // -----------------------------------------------------------------------
    // Validation: missing locationId → 400 Bad Request
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Validation: GET /today without locationId returns 400 Bad Request")
    void getDashboard_missingLocationId_returns400() throws Exception {
        // Issue CAP-142: missing required @RequestParam → Spring MVC 400
        mockMvc.perform(get(DASHBOARD_URL))
                .andExpect(status().isBadRequest());
    }

    // -----------------------------------------------------------------------
    // AC-5 (controller): explicit date param is forwarded to service
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("AC-5: ?date=2026-03-01 is parsed and forwarded to DashboardService")
    void getDashboard_withDateParam_passesDateToService() throws Exception {
        // Arrange
        // Issue CAP-142: AC-5 — explicit date is forwarded correctly
        DashboardResponse stub = DashboardResponse.builder()
                .locationId("LOC-123")
                .date(LocalDate.of(2026, 3, 1))
                .workorders(List.of())
                .mechanics(List.of())
                .bays(List.of())
                .conflicts(List.of())
                .lastRefreshed(Instant.now())
                .build();
        when(dashboardService.getDashboard(any(), any(LocalDate.class))).thenReturn(stub);

        // Act
        mockMvc.perform(get(DASHBOARD_URL)
                .param("locationId", "LOC-123")
                .param("date", "2026-03-01"))
                .andExpect(status().isOk());

        // Assert: service received the exact parsed date
        verify(dashboardService).getDashboard(eq("LOC-123"), eq(LocalDate.of(2026, 3, 1)));
    }

    // -----------------------------------------------------------------------
    // AC-5 (controller): no date param → service receives LocalDate.now()
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("AC-5: No date param defaults to today (LocalDate.now()) when calling DashboardService")
    void getDashboard_withDefaultDate_usesToday() throws Exception {
        // Arrange
        // Issue CAP-142: AC-5 — default date is today
        LocalDate today = LocalDate.now();
        DashboardResponse stub = DashboardResponse.builder()
                .locationId("LOC-123")
                .date(today)
                .workorders(List.of())
                .mechanics(List.of())
                .bays(List.of())
                .conflicts(List.of())
                .lastRefreshed(Instant.now())
                .build();
        when(dashboardService.getDashboard(any(), any(LocalDate.class))).thenReturn(stub);

        // Act
        mockMvc.perform(get(DASHBOARD_URL).param("locationId", "LOC-123"))
                .andExpect(status().isOk());

        // Assert: service receives today's date (no date param → LocalDate.now())
        verify(dashboardService).getDashboard(eq("LOC-123"), eq(today));
    }
}
