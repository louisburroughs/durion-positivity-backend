package com.positivity.mcp.internal.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.mcp.internal.dto.WritePlanResponseV1;
import com.positivity.mcp.internal.exception.WritePlanExpiredException;
import com.positivity.mcp.internal.exception.WritePlanStaleException;
import com.positivity.mcp.internal.service.NltiWorkflowStateService;
import com.positivity.mcp.internal.service.NltiWritePlanService;
import com.positivity.mcp.service.NltiRequestService;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.AuthenticationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Gate 6 (#1193) slice tests for the write-plan confirm/cancel endpoints:
 * 200 flows, expired/stale error mapping, and authentication gating.
 */
@WebMvcTest(NltiController.class)
@ActiveProfiles("test")
class NltiWritePlanEndpointsTest {

    // Hardcoded test UUIDs — no UUID.randomUUID() per ADR
    private static final UUID REQUEST_ID = UUID.fromString("00000000-0000-7000-8000-000000000201");
    private static final UUID SESSION_ID = UUID.fromString("00000000-0000-7000-8000-000000000202");
    private static final UUID PLAN_ID = UUID.fromString("00000000-0000-7000-8000-000000000203");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private NltiRequestService nltiRequestService;

    @MockitoBean
    private NltiWorkflowStateService workflowStateService;

    @MockitoBean
    private NltiWritePlanService writePlanService;

    private static WritePlanResponseV1 response(String status, String executionResult) {
        return new WritePlanResponseV1(
                PLAN_ID,
                REQUEST_ID,
                SESSION_ID,
                status,
                "purchaseOrderCreate",
                Map.of("poNumber", "PO-77"),
                Set.of(),
                "MEDIUM",
                "Will call purchaseOrderCreate",
                "idem-key-1",
                OffsetDateTime.of(2026, 8, 7, 12, 10, 0, 0, ZoneOffset.UTC),
                null,
                executionResult);
    }

    @Test
    @NltiControllerTest.WithGatewayUser(username = "alice", authorities = "nlti:request:submit")
    @DisplayName("POST /v1/nlt/requests/{id}/confirm returns 200 with the execution outcome")
    void confirm_returns200WithOutcome() throws Exception {
        when(writePlanService.confirm(eq(REQUEST_ID), eq("alice"), any(), eq("idem-key-1"), isNull()))
                .thenReturn(response("COMPLETE", "created PO-77"));

        mockMvc.perform(post("/v1/nlt/requests/" + REQUEST_ID + "/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idempotencyKey\":\"idem-key-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.planId").value(PLAN_ID.toString()))
                .andExpect(jsonPath("$.status").value("COMPLETE"))
                .andExpect(jsonPath("$.executionResult").value("created PO-77"));
    }

    @Test
    @NltiControllerTest.WithGatewayUser(username = "alice", authorities = "nlti:request:submit")
    @DisplayName("confirm of an expired plan maps to 410 WRITE_PLAN_EXPIRED")
    void confirm_expired_maps410() throws Exception {
        when(writePlanService.confirm(eq(REQUEST_ID), eq("alice"), any(), isNull(), isNull()))
                .thenThrow(new WritePlanExpiredException("Write plan expired"));

        mockMvc.perform(post("/v1/nlt/requests/" + REQUEST_ID + "/confirm"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("WRITE_PLAN_EXPIRED"));
    }

    @Test
    @NltiControllerTest.WithGatewayUser(username = "alice", authorities = "nlti:request:submit")
    @DisplayName("confirm against changed source data maps to 409 WRITE_PLAN_STALE")
    void confirm_stale_maps409() throws Exception {
        when(writePlanService.confirm(eq(REQUEST_ID), eq("alice"), any(), isNull(), isNull()))
                .thenThrow(new WritePlanStaleException("Source data changed"));

        mockMvc.perform(post("/v1/nlt/requests/" + REQUEST_ID + "/confirm"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WRITE_PLAN_STALE"));
    }

    @Test
    @DisplayName("confirm without authentication returns 401")
    void confirm_withoutAuth_returns401() throws Exception {
        mockMvc.perform(post("/v1/nlt/requests/" + REQUEST_ID + "/confirm")).andExpect(status().isUnauthorized());
    }

    @Test
    @NltiControllerTest.WithGatewayUser(username = "alice", authorities = "nlti:request:submit")
    @DisplayName("POST /v1/nlt/requests/{id}/cancel returns 200 with the cancelled plan")
    void cancel_returns200() throws Exception {
        when(writePlanService.cancel(REQUEST_ID, "alice")).thenReturn(response("CANCELLED", null));

        mockMvc.perform(post("/v1/nlt/requests/" + REQUEST_ID + "/cancel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    @DisplayName("cancel without authentication returns 401")
    void cancel_withoutAuth_returns401() throws Exception {
        mockMvc.perform(post("/v1/nlt/requests/" + REQUEST_ID + "/cancel")).andExpect(status().isUnauthorized());
    }

    @TestConfiguration
    @EnableMethodSecurity(prePostEnabled = true)
    static class SliceTestConfig {

        @Bean
        SecurityExceptionControllerAdvice securityExceptionControllerAdvice() {
            return new SecurityExceptionControllerAdvice();
        }
    }

    /** Maps Spring Security exceptions to 401/403 in the MockMvc slice (ADR-0017). */
    @ControllerAdvice
    static class SecurityExceptionControllerAdvice {

        @ExceptionHandler(AccessDeniedException.class)
        @ResponseStatus(HttpStatus.FORBIDDEN)
        void handleAccessDenied() {
            // HTTP 403 set by @ResponseStatus
        }

        @ExceptionHandler(AuthenticationException.class)
        @ResponseStatus(HttpStatus.UNAUTHORIZED)
        void handleAuthenticationException() {
            // HTTP 401 set by @ResponseStatus
        }
    }
}
