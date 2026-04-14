package com.positivity.shopmanager.internal.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.shopmanager.service.AssignmentService;
import com.positivity.shopmanager.service.dto.AssignmentResponse;
import com.positivity.shopmanager.service.enums.AssignmentStatus;
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
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Controller slice tests for CAP-249 Story #10: Appointment Assignment Show —
 * verifies that {@link AssignmentController#listAssignments} enforces the
 * canonical read authorities ({@code appointments:view} or
 * {@code shop:schedule:view}) (AC4, ADR-0011) and returns HTTP
 * 200 for
 * authorized callers; HTTP 403 for unauthorized callers (ADR-0017).
 *
 * <p>
 * {@code @WithMockUser} pre-populates the security context so that
 * {@code @PreAuthorize} expressions are evaluated deterministically without
 * requiring the full gateway filter chain.
 *
 * <p>
 * In this Spring Boot 4 / Spring Security 6.4 slice context, method-security
 * {@code AccessDeniedException} propagates past the default
 * {@code ExceptionTranslationFilter}. The inner
 * {@link SecurityExceptionControllerAdvice} converts it to HTTP 403 so that
 * assertions remain HTTP-level as documented in ADR-0017.
 *
 * Issue: #10
 */
@WebMvcTest(AssignmentController.class)
class AssignmentControllerStory10Test {
    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AssignmentService assignmentService;

    // Issue #10: AC4 — appointments:view authority grants access

    /**
     * Verifies AC4 happy path: a caller with the {@code appointments:view}
     * authority receives HTTP 200 and a JSON array body.
     */
    @Test
    @WithMockUser(authorities = "appointments:view")
    void listAssignments_withViewAssignmentsAuthority_returns200() throws Exception {
        UUID appointmentId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID assignmentId = UUID.fromString("00000000-0000-0000-0000-000000000001");

        AssignmentResponse mockResponse = AssignmentResponse.builder()
                .assignmentId(assignmentId)
                .appointmentId(appointmentId)
                .status(AssignmentStatus.CONFIRMED)
                .mechanics(List.of())
                .build();

        when(assignmentService.getByAppointmentId(any(UUID.class))).thenReturn(List.of(mockResponse));

        mockMvc.perform(get("/v1/appointments/{appointmentId}/assignments", appointmentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].assignmentId").value(assignmentId.toString()));
    }

    // Issue #10: AC4 — shop:schedule:view is also a valid read authority

    /**
     * Verifies that a caller with {@code shop:schedule:view}
     * authority also receives HTTP 200 (ADR-0017 compliant).
     */
    @Test
    @WithMockUser(authorities = "shop:schedule:view")
    void listAssignments_withScheduleViewAuthority_returns200() throws Exception {
        UUID appointmentId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        when(assignmentService.getByAppointmentId(any(UUID.class))).thenReturn(List.of());

        mockMvc.perform(get("/v1/appointments/{appointmentId}/assignments", appointmentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    // Issue #10: AC4 — missing read authority is denied

    /**
     * Verifies AC4 denial: a caller without {@code appointments:view} or
     * {@code shop:schedule:view} receives HTTP 403 (ADR-0017).
     */
    @Test
    @WithMockUser(authorities = "some.unrelated.authority")
    void listAssignments_withoutAuthority_returns403() throws Exception {
        UUID appointmentId = UUID.fromString("00000000-0000-0000-0000-000000000001");

        mockMvc.perform(get("/v1/appointments/{appointmentId}/assignments", appointmentId))
                .andExpect(status().isForbidden());
    }

    // Issue #10: AC4 — appointments:create alone does NOT grant read access

    /**
     * Verifies that holding only {@code appointments:create}
     * (a write-verb authority) does not grant access to the GET list endpoint.
     */
    @Test
    @WithMockUser(authorities = "appointments:create")
    void listAssignments_withCreateOnlyAuthority_returns403() throws Exception {
        UUID appointmentId = UUID.fromString("00000000-0000-0000-0000-000000000001");

        mockMvc.perform(get("/v1/appointments/{appointmentId}/assignments", appointmentId))
                .andExpect(status().isForbidden());
    }

    /**
     * Minimal supplementary configuration for the {@code @WebMvcTest} slice.
     *
     * <p>
     * Enables method-level security so {@code @PreAuthorize} on
     * {@link AssignmentController} is evaluated.
     *
     * <p>
     * Also registers {@link SecurityExceptionControllerAdvice} to convert
     * {@link AccessDeniedException} to HTTP 403 within the MockMvc dispatcher,
     * since in Spring Boot 4 / Spring Security 6.4 the
     * {@code ExceptionTranslationFilter} does not handle method-security
     * {@code AccessDeniedException} before MockMvc captures the exception.
     */
    @TestConfiguration
    @EnableMethodSecurity(prePostEnabled = true)
    static class SliceTestConfig {

        @Bean
        Clock clock() {
            return TEST_CLOCK;
        }

        @Bean
        SecurityExceptionControllerAdvice securityExceptionControllerAdvice() {
            return new SecurityExceptionControllerAdvice();
        }
    }

    /**
     * Maps Spring Security {@link AccessDeniedException} (and its subclass
     * {@code AuthorizationDeniedException}) thrown by {@code @PreAuthorize}
     * in this slice context to HTTP 403 Forbidden.
     */
    @ControllerAdvice
    static class SecurityExceptionControllerAdvice {

        @ExceptionHandler(AccessDeniedException.class)
        @ResponseStatus(HttpStatus.FORBIDDEN)
        void handleAccessDenied() {
            // status set by @ResponseStatus
        }
    }
}
