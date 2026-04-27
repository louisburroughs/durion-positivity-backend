package com.positivity.securityservice.internal.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.securityservice.internal.dto.AuditExportJobResponse;
import com.positivity.securityservice.internal.dto.AuditLogEventDto;
import com.positivity.securityservice.internal.enums.AuditExportStatus;
import com.positivity.securityservice.internal.security.JwtAuthenticationFilter;
import com.positivity.securityservice.internal.service.CustomUserDetailsService;
import com.positivity.securityservice.service.AuditEventService;
import com.positivity.securityservice.service.AuditExportService;
import com.positivity.securityservice.service.PricingSnapshotService;
import jakarta.persistence.EntityNotFoundException;
import jakarta.servlet.FilterChain;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Controller slice tests for B-3 search and B-4 export endpoints from the SDK
 * migration Wave 1.
 */
@WebMvcTest({ AuditController.class, AuditExportController.class })
@DisplayName("AuditControllerTest — B-3 searchAuditEvents, B-4 export endpoints")
class AuditControllerTest {

  private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
  private static final UUID KNOWN_JOB_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final UUID UNKNOWN_JOB_ID = UUID.fromString("00000000-0000-0000-0000-000000000099");
  private static final UUID WORKORDER_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

  @Autowired
  private MockMvc mockMvc;

  // ── Service dependencies ──────────────────────────────────────────────────

  @MockitoBean
  private AuditEventService auditEventService;

  @MockitoBean
  private AuditExportService auditExportService;

  @MockitoBean
  private PricingSnapshotService pricingSnapshotService;

  // ── Security infrastructure ───────────────────────────────────────────────

  @MockitoBean
  private JwtAuthenticationFilter jwtAuthenticationFilter;

  @MockitoBean
  private CustomUserDetailsService customUserDetailsService;

  @BeforeEach
  void configureJwtFilterPassthrough() throws Exception {
    doAnswer(inv -> {
      ((FilterChain) inv.getArgument(2)).doFilter(inv.getArgument(0), inv.getArgument(1));
      return null;
    })
        .when(jwtAuthenticationFilter)
        .doFilter(any(), any(), any());
  }

  // ── B-3: GET /v1/audit/events ─────────────────────────────────────────────

  /**
   * B-3: GET /v1/audit/events?page=0&size=10 returns 200 with a paged result.
   */
  @Test
  @WithMockUser(authorities = "security:audit:view")
  @DisplayName("GET /v1/audit/events?page=0&size=10 → 200 with page")
  void searchAuditEvents_noFilters_returnsPage() throws Exception {
    AuditLogEventDto event = AuditLogEventDto.builder()
        .eventId(UUID.randomUUID())
        .timestamp(Instant.parse("2026-01-15T10:00:00Z"))
        .eventType("PERMISSION_DENIED")
        .actorId("user@example.com")
        .entityId("entity-1")
        .entityType("Permission")
        .build();
    when(auditEventService.searchEventsFiltered(any(), any()))
        .thenReturn(new PageImpl<>(List.of(event)));

    mockMvc.perform(get("/v1/audit/events").param("page", "0").param("size", "10"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content").isArray())
        .andExpect(jsonPath("$.content[0].eventType").value("PERMISSION_DENIED"));
  }

  /**
   * B-3: GET /v1/audit/events?actorId=<string> returns 200 with filtered results.
   */
  @Test
  @WithMockUser(authorities = "security:audit:view")
  @DisplayName("GET /v1/audit/events?actorId=<string> → 200 with filtered page")
  void searchAuditEvents_withActorId_returnsFilteredPage() throws Exception {
    String actorId = "advisor.jane";
    AuditLogEventDto event = AuditLogEventDto.builder()
        .eventId(UUID.randomUUID())
        .timestamp(Instant.parse("2026-02-01T08:00:00Z"))
        .eventType("LOGIN_SUCCESS")
        .actorId(actorId)
        .entityId("session-1")
        .entityType("Session")
        .build();
    when(auditEventService.searchEventsFiltered(any(), any()))
        .thenReturn(new PageImpl<>(List.of(event)));

    mockMvc.perform(get("/v1/audit/events").param("actorId", actorId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content").isArray())
        .andExpect(jsonPath("$.content[0].actorId").value(actorId));
  }

  /**
   * B-3: GET /v1/audit/events?workorderId=<uuid> returns 200 with filtered
   * results.
   */
  @Test
  @WithMockUser(authorities = "security:audit:view")
  @DisplayName("GET /v1/audit/events?workorderId=<uuid> → 200 with filtered page")
  void searchAuditEvents_withWorkorderId_returnsFilteredPage() throws Exception {
    AuditLogEventDto event = AuditLogEventDto.builder()
        .eventId(UUID.randomUUID())
        .timestamp(Instant.parse("2026-03-01T12:00:00Z"))
        .eventType("WORKORDER_CREATED")
        .actorId("advisor@shop.com")
        .entityId(WORKORDER_ID.toString())
        .entityType("Workorder")
        .build();
    when(auditEventService.searchEventsFiltered(any(), any()))
        .thenReturn(new PageImpl<>(List.of(event)));

    mockMvc.perform(get("/v1/audit/events").param("workorderId", WORKORDER_ID.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content").isArray())
        .andExpect(jsonPath("$.content[0].entityType").value("Workorder"));
  }

  /**
   * B-3: GET /v1/audit/events?fromDate=...&toDate=... returns 200 with
   * date-scoped page.
   */
  @Test
  @WithMockUser(authorities = "security:audit:view")
  @DisplayName("GET /v1/audit/events?fromDate=...&toDate=... → 200 with date-filtered page")
  void searchAuditEvents_withDateRange_returnsFilteredPage() throws Exception {
    AuditLogEventDto event = AuditLogEventDto.builder()
        .eventId(UUID.randomUUID())
        .timestamp(Instant.parse("2026-06-15T09:00:00Z"))
        .eventType("ROLE_ASSIGNED")
        .actorId("admin@shop.com")
        .entityId("role-1")
        .entityType("Role")
        .build();
    when(auditEventService.searchEventsFiltered(any(), any()))
        .thenReturn(new PageImpl<>(List.of(event)));

    mockMvc.perform(get("/v1/audit/events")
        .param("fromDate", "2026-01-01T00:00:00Z")
        .param("toDate", "2026-12-31T23:59:59Z"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content").isArray())
        .andExpect(jsonPath("$.content[0].eventType").value("ROLE_ASSIGNED"));
  }

  /**
   * B-3: GET /v1/audit/events without the required authority returns 403.
   */
  @Test
  @WithMockUser(roles = "VIEWER")
  @DisplayName("GET /v1/audit/events without security:audit:view authority → 403 Forbidden")
  void searchAuditEvents_missingAuthority_returns403() throws Exception {
    mockMvc.perform(get("/v1/audit/events"))
        .andExpect(status().isForbidden());
  }

  // ── B-4: POST /v1/audit/exports ───────────────────────────────────────────

  /**
   * B-4: POST /v1/audit/exports with a valid body returns 202 Accepted.
   */
  @Test
  @WithMockUser(authorities = "security:audit:export")
  @DisplayName("POST /v1/audit/exports with valid request → 202 Accepted")
  void requestAuditExport_validRequest_returns202() throws Exception {
    AuditExportJobResponse stub = AuditExportJobResponse.builder()
        .jobId(KNOWN_JOB_ID)
        .status(AuditExportStatus.PENDING)
        .requestedAt(Instant.parse("2026-01-01T00:00:00Z"))
        .build();
    when(auditExportService.requestExport(any())).thenReturn(stub);

    mockMvc.perform(post("/v1/audit/exports")
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"format\":\"CSV\",\"deliveryMode\":\"DOWNLOAD\"}"))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.jobId").value(KNOWN_JOB_ID.toString()))
        .andExpect(jsonPath("$.status").value("PENDING"));
  }

  /**
   * B-4: POST /v1/audit/exports without the required authority returns 403.
   */
  @Test
  @WithMockUser(roles = "VIEWER")
  @DisplayName("POST /v1/audit/exports without security:audit:export authority → 403 Forbidden")
  void requestAuditExport_missingAuthority_returns403() throws Exception {
    mockMvc.perform(post("/v1/audit/exports")
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"format\":\"CSV\",\"deliveryMode\":\"DOWNLOAD\"}"))
        .andExpect(status().isForbidden());
  }

  // ── B-4: GET /v1/audit/exports/{jobId} ───────────────────────────────────

  /**
   * B-4: GET /v1/audit/exports/{jobId} for a known job returns 200 with job
   * response.
   */
  @Test
  @WithMockUser(authorities = "security:audit:export")
  @DisplayName("GET /v1/audit/exports/{jobId} for known job → 200 with job response")
  void getAuditExportJob_existingJob_returns200() throws Exception {
    AuditExportJobResponse stub = AuditExportJobResponse.builder()
        .jobId(KNOWN_JOB_ID)
        .status(AuditExportStatus.PENDING)
        .requestedAt(Instant.parse("2026-01-01T00:00:00Z"))
        .build();
    when(auditExportService.getExportJob(KNOWN_JOB_ID)).thenReturn(stub);

    mockMvc.perform(get("/v1/audit/exports/{jobId}", KNOWN_JOB_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.jobId").value(KNOWN_JOB_ID.toString()))
        .andExpect(jsonPath("$.status").value("PENDING"));
  }

  /**
   * B-4: GET /v1/audit/exports/{jobId} for an unknown job returns 404.
   */
  @Test
  @WithMockUser(authorities = "security:audit:export")
  @DisplayName("GET /v1/audit/exports/{unknownId} → 404 Not Found")
  void getAuditExportJob_unknownJob_returns404() throws Exception {
    when(auditExportService.getExportJob(UNKNOWN_JOB_ID))
        .thenThrow(new EntityNotFoundException("Audit export job not found: " + UNKNOWN_JOB_ID));

    mockMvc.perform(get("/v1/audit/exports/{jobId}", UNKNOWN_JOB_ID))
        .andExpect(status().isNotFound());
  }

  /**
   * B-4: GET /v1/audit/exports/{jobId} without the required authority returns
   * 403.
   */
  @Test
  @WithMockUser(roles = "VIEWER")
  @DisplayName("GET /v1/audit/exports/{jobId} without security:audit:export authority → 403 Forbidden")
  void getAuditExportJob_missingAuthority_returns403() throws Exception {
    mockMvc.perform(get("/v1/audit/exports/{jobId}", KNOWN_JOB_ID))
        .andExpect(status().isForbidden());
  }

  // ── Test slice configuration ──────────────────────────────────────────────

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
   * Maps Spring Security exceptions to HTTP 401/403 in this MockMvc slice.
   * {@code @Order(HIGHEST_PRECEDENCE)} ensures this advice is consulted before
   * {@link com.positivity.securityservice.internal.config.GlobalExceptionHandler}
   * which would otherwise return 500 for unhandled security exceptions.
   */
  @ControllerAdvice
  @Order(Ordered.HIGHEST_PRECEDENCE)
  static class SecurityExceptionControllerAdvice {

    @ExceptionHandler(AccessDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    void handleAccessDenied() {
      // Intentionally empty: @ResponseStatus drives the HTTP mapping in this slice.
    }

    @ExceptionHandler(AuthenticationException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    void handleUnauthenticated() {
      // Intentionally empty: @ResponseStatus drives the HTTP mapping in this slice.
    }

    @ExceptionHandler(NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    void handleNotFound() {
      // Intentionally empty: @ResponseStatus drives the HTTP mapping in this slice.
    }
  }
}
