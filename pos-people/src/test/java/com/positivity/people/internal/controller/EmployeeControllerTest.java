package com.positivity.people.internal.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.people.config.TestSecurityConfig;
import com.positivity.people.internal.dto.EmployeeSummaryDto;
import com.positivity.people.internal.dto.PagedResponse;
import com.positivity.people.internal.service.EmployeeService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(EmployeeController.class)
@Import({TestSecurityConfig.class, EmployeeControllerTest.FixedClockConfig.class})
@ActiveProfiles("test")
@SuppressWarnings({"java:S6813", "java:S100", "java:S1192"})
class EmployeeControllerTest {

    private static final UUID EMPLOYEE_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4c01");
    private static final UUID PERSON_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4c02");

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    EmployeeService employeeService;

    private EmployeeSummaryDto summary() {
        return EmployeeSummaryDto.builder()
                .employeeId(EMPLOYEE_ID)
                .personId(PERSON_ID)
                .employeeNumber("EMP-0001")
                .firstName("Jane")
                .lastName("Smith")
                .preferredName("Janie")
                .status("ACTIVE")
                .active(true)
                .build();
    }

    // ─── GET /v1/people/employees — 200 OK ────────────────────────────────

    @Test
    void searchEmployees_returnsOkWithMatchingResults_whenCallerHoldsThePermission() throws Exception {
        PagedResponse<EmployeeSummaryDto> page = new PagedResponse<>(List.of(summary()), 0, 20, 1, 1);
        when(employeeService.searchEmployees(eq("smith"), eq(0), eq(20))).thenReturn(page);

        mockMvc.perform(get("/v1/people/employees").param("q", "smith").header("X-Authorities", "people:employee:view"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].employeeNumber").value("EMP-0001"))
                .andExpect(jsonPath("$.items[0].firstName").value("Jane"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20));
    }

    @Test
    void searchEmployees_appliesDefaultPagingWhenOmitted() throws Exception {
        PagedResponse<EmployeeSummaryDto> page = new PagedResponse<>(List.of(), 0, 20, 0, 0);
        when(employeeService.searchEmployees(eq(null), eq(0), eq(20))).thenReturn(page);

        mockMvc.perform(get("/v1/people/employees").header("X-Authorities", "people:employee:view"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());
    }

    @Test
    void searchEmployees_returnsForbidden_whenCallerLacksThePermission() throws Exception {
        mockMvc.perform(get("/v1/people/employees").header("X-Authorities", "people:employee:create"))
                .andExpect(status().isForbidden());
    }

    @Test
    void searchEmployees_rejectsASizeAboveTheMaximum() throws Exception {
        mockMvc.perform(get("/v1/people/employees")
                        .param("size", "101")
                        .header("X-Authorities", "people:employee:view"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void searchEmployees_rejectsANegativePage() throws Exception {
        mockMvc.perform(get("/v1/people/employees").param("page", "-1").header("X-Authorities", "people:employee:view"))
                .andExpect(status().isBadRequest());
    }

    /**
     * A real fixed {@code Clock}, not a mock. {@code PeopleExceptionHandler} reads it on every
     * error response ({@code Instant.now(clock)}), and an unstubbed mock returns {@code null} —
     * which made the advice itself throw, so the original exception surfaced as unhandled
     * (issue #1716). Fixed rather than {@code systemUTC} so timestamps stay deterministic.
     */
    @TestConfiguration
    static class FixedClockConfig {

        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-03-01T00:00:00Z"), ZoneOffset.UTC);
        }
    }
}
