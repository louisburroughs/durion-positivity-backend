package com.positivity.mcp.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.mcp.internal.dto.AuditEventResponse;
import com.positivity.mcp.internal.dto.AuditQuery;
import com.positivity.mcp.internal.exception.InvalidAuditEventTypeException;
import com.positivity.mcp.service.AuditLedgerService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Controller slice tests for NLTI-007 — Audit Ledger Query API.
 *
 * <p>Verifies that {@link AuditController}:
 * <ul>
 * <li>Returns HTTP 200 with paginated audit events for authenticated callers
 * with {@code nlti:audit:read} authority.</li>
 * <li>Supports filtering by {@code correlationId}, date range, and
 * {@code eventType}.</li>
 * <li>Returns HTTP 403 for callers without {@code nlti:audit:read}
 * authority.</li>
 * <li>Returns HTTP 401 for unauthenticated callers (ADR-0017).</li>
 * </ul>
 *
 * Issue: NLTI-007
 */
@WebMvcTest(AuditController.class)
@ActiveProfiles("test")
class AuditControllerTest {

    // Hardcoded test UUIDs — no UUID.randomUUID() per ADR-0013
    private static final UUID CORRELATION_ID = UUID.fromString("00000000-0000-7000-8000-000000000200");
    private static final UUID EVENT_ID = UUID.fromString("00000000-0000-7000-8000-000000000203");

    private static final Instant FIXED_INSTANT = Instant.parse("2026-01-01T12:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuditLedgerService auditLedgerService;

    // ─── AC-2: GET with correlationId → 200 with page ─────────────────────────

    /**
     * Authenticated user with {@code nlti:audit:read} authority querying by
     * correlationId must receive HTTP 200 with the page's first event id visible
     * in the JSON response.
     *
     * <p>RED: AuditController throws UnsupportedOperationException before
     * delegating to the service — MockMvc receives HTTP 500 instead of 200;
     * status assertion fails.
     */
    @Test
    @WithMockUser(authorities = "nlti:audit:read")
    @DisplayName("GET /v1/nlt/audit?correlationId → 200 with page containing eventId")
    void queryAudit_withCorrelationId_returns200WithPage() throws Exception {
        AuditEventResponse eventResponse =
                new AuditEventResponse(EVENT_ID, CORRELATION_ID, "REQUEST", FIXED_INSTANT, "audit-user", null);
        Page<AuditEventResponse> page = new PageImpl<>(List.of(eventResponse));
        when(auditLedgerService.query(any(), any())).thenReturn(page);

        // Issue NLTI-007: controller must delegate to service and return 200 with page
        mockMvc.perform(get("/v1/nlt/audit").param("correlationId", CORRELATION_ID.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].eventId").value(EVENT_ID.toString()));
    }

    // ─── AC-2: GET with date range → 200 ─────────────────────────────────────

    /**
     * Authenticated query with {@code from} and {@code to} ISO-8601 date-time
     * parameters must return HTTP 200 with a (possibly empty) page.
     *
     * <p>RED: AuditController throws UnsupportedOperationException → HTTP 500.
     */
    @Test
    @WithMockUser(authorities = "nlti:audit:read")
    @DisplayName("GET /v1/nlt/audit?from=&to= → 200 with page")
    void queryAudit_withDateRange_returns200() throws Exception {
        when(auditLedgerService.query(any(), any())).thenReturn(new PageImpl<>(List.of()));

        // Issue NLTI-007: date-range filter must produce a 200 response
        mockMvc.perform(get("/v1/nlt/audit")
                        .param("from", "2026-01-01T00:00:00Z")
                        .param("to", "2026-01-01T23:59:59Z"))
                .andExpect(status().isOk());
    }

    // ─── AC-2: GET with eventType filter → 200 ───────────────────────────────

    /**
     * Authenticated query filtered by {@code eventType=REQUEST} must return
     * HTTP 200.
     *
     * <p>RED: AuditController throws UnsupportedOperationException → HTTP 500.
     */
    @Test
    @WithMockUser(authorities = "nlti:audit:read")
    @DisplayName("GET /v1/nlt/audit?eventType=REQUEST → 200")
    void queryAudit_withEventTypeFilter_returns200() throws Exception {
        when(auditLedgerService.query(any(), any())).thenReturn(new PageImpl<>(List.of()));

        // Issue NLTI-007: eventType filter must produce a 200 response
        mockMvc.perform(get("/v1/nlt/audit").param("eventType", "REQUEST")).andExpect(status().isOk());

        ArgumentCaptor<AuditQuery> queryCaptor = ArgumentCaptor.forClass(AuditQuery.class);
        verify(auditLedgerService).query(queryCaptor.capture(), any());
        assertThat(queryCaptor.getValue().eventType()).isEqualTo("REQUEST");
    }

    @Test
    @WithMockUser(authorities = "nlti:audit:read")
    @DisplayName("GET /v1/nlt/audit?eventType=invalid → 400 with clear message")
    void queryAudit_withInvalidEventType_returns400WithClearMessage() throws Exception {
        when(auditLedgerService.query(any(), any()))
                .thenThrow(new InvalidAuditEventTypeException(
                        "Invalid eventType 'requestx'. Supported values: REQUEST, INTENT"));

        mockMvc.perform(get("/v1/nlt/audit").param("eventType", "requestx"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("ERROR"))
                .andExpect(jsonPath("$.code").value("INVALID_EVENT_TYPE"))
                .andExpect(
                        jsonPath("$.message").value("Invalid eventType 'requestx'. Supported values: REQUEST, INTENT"));
    }

    // ─── ADR-0017: missing authority → 403 ────────────────────────────────────

    /**
     * A caller with a valid authentication but without the
     * {@code nlti:audit:read} authority must receive HTTP 403 Forbidden.
     *
     * <p>GREEN from the start: {@code @PreAuthorize} fires before the controller
     * body, so this test passes even while the controller stub throws UOE.
     */
    @Test
    @WithMockUser(authorities = "other:authority")
    @DisplayName("GET /v1/nlt/audit without nlti:audit:read → 403 Forbidden")
    void queryAudit_withoutAuthority_returns403() throws Exception {
        // Issue NLTI-007: RBAC enforcement via @PreAuthorize("hasAuthority('nlti:audit:read')")
        mockMvc.perform(get("/v1/nlt/audit")).andExpect(status().isForbidden());
    }

    // ─── ADR-0017: no authentication → 401 ───────────────────────────────────

    /**
     * An unauthenticated caller must receive HTTP 401 Unauthorized (ADR-0017).
     *
     * <p>GREEN from the start: {@code @PreAuthorize} fires before the controller
     * body, so this test passes even while the controller stub throws UOE.
     */
    @Test
    @DisplayName("GET /v1/nlt/audit unauthenticated → 401 Unauthorized")
    void queryAudit_unauthenticated_returns401() throws Exception {
        // Issue NLTI-007: no @WithMockUser → Spring Security → 401
        mockMvc.perform(get("/v1/nlt/audit")).andExpect(status().isUnauthorized());
    }

    // ─── Test slice configuration ─────────────────────────────────────────────

    /**
     * Minimal test-slice configuration for AuditControllerTest:
     * <ul>
     * <li>Enables {@code @PreAuthorize} evaluation so 401/403 tests function.</li>
     * <li>Provides {@link SecurityExceptionControllerAdvice} to map Spring Security
     * exceptions to the correct HTTP status codes (ADR-0017).</li>
     * </ul>
     *
     * Issue: NLTI-007
     */
    @TestConfiguration
    @EnableMethodSecurity(prePostEnabled = true)
    static class SliceTestConfig {

        @Bean
        SecurityExceptionControllerAdvice securityExceptionControllerAdvice() {
            return new SecurityExceptionControllerAdvice();
        }
    }

    /**
     * Maps Spring Security {@link AccessDeniedException} to HTTP 403 Forbidden and
     * {@link AuthenticationException} subtypes to HTTP 401 Unauthorized in the
     * MockMvc test-slice context (mirrors NltiControllerTest pattern per ADR-0017).
     */
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
