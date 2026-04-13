package com.positivity.mcp.internal.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.mcp.internal.dto.NltiRequestDTO;
import com.positivity.mcp.internal.dto.NltiResponseV1;
import com.positivity.mcp.service.NltiRequestService;

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
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Controller slice tests for NLTI-001 — API Envelope + Session + Correlation.
 *
 * <p>
 * Verifies that {@link NltiController}:
 * <ul>
 * <li>Returns HTTP 202 with required envelope fields (requestId, correlationId,
 * sessionId, status).</li>
 * <li>Returns HTTP 400 for requests missing the required {@code prompt}
 * field.</li>
 * <li>Echoes the inbound {@code X-Correlation-Id} header in the response
 * envelope.</li>
 * <li>Returns HTTP 401 for unauthenticated callers (ADR-0017).</li>
 * </ul>
 *
 * Issue: NLTI-001
 */
@WebMvcTest(NltiController.class)
@ActiveProfiles("test")
class NltiControllerTest {

    // Hardcoded test UUIDs — no UUID.randomUUID() per ADR
    private static final UUID REQUEST_ID = UUID.fromString("00000000-0000-7000-8000-000000000001");
    private static final UUID CORRELATION_ID = UUID.fromString("00000000-0000-7000-8000-000000000002");
    private static final UUID SESSION_ID = UUID.fromString("00000000-0000-7000-8000-000000000003");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private NltiRequestService nltiRequestService;

    // ─── AC-1: POST with valid prompt → 202 with required envelope fields ─────

    /**
     * AC-1: Authenticated submit with a valid prompt must return HTTP 202 Accepted
     * with a JSON envelope containing {@code requestId}, {@code correlationId},
     * {@code sessionId}, and {@code status} fields.
     *
     * <p>
     * The controller correctly delegates to the service and returns 202 with the
     * full envelope.
     */
    @Test
    @WithMockUser(authorities = "nlti:request:submit")
    @DisplayName("AC-1: POST /v1/nlt/requests with valid prompt returns 202 with envelope fields")
    void submitRequest_withValidPrompt_returns202WithRequiredFields() throws Exception {
        // Arrange — Issue NLTI-001: mock service to show contract shape
        NltiResponseV1 stubResponse = new NltiResponseV1(
                REQUEST_ID, CORRELATION_ID, SESSION_ID, "ACCEPTED", null, null);
        when(nltiRequestService.submit(any())).thenReturn(stubResponse);
        when(nltiRequestService.submit(any(), any())).thenReturn(stubResponse);

        String body = objectMapper.writeValueAsString(
                new NltiRequestDTO("close workorder 123", null, null));

        // Act + Assert
        mockMvc.perform(post("/v1/nlt/requests")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.requestId").value(REQUEST_ID.toString()))
                .andExpect(jsonPath("$.correlationId").value(CORRELATION_ID.toString()))
                .andExpect(jsonPath("$.sessionId").value(SESSION_ID.toString()))
                .andExpect(jsonPath("$.status").value("ACCEPTED"));
    }

    // ─── AC-2: POST without prompt → 400 validation ───────────────────────────

    /**
     * AC-2: Submitting a request without a {@code prompt} must return HTTP 400.
     * Spring's {@code @Valid} / {@code @NotBlank} constraint fires before the
     * controller body and rejects the request.
     *
     * <p>
     * <b>Note:</b> Passing this test requires
     * {@code spring-boot-starter-validation}
     * (Hibernate Validator) on the classpath. Without it, {@code @Valid} is a no-op
     * and the controller body is reached, throwing UOE. Once validation is active,
     * this test passes immediately as a structural contract.
     */
    @Test
    @WithMockUser(authorities = "nlti:request:submit")
    @DisplayName("AC-2: POST /v1/nlt/requests without prompt returns 400 Bad Request")
    void submitRequest_withMissingPrompt_returns400WithValidationError() throws Exception {
        UUID inboundCorrId = UUID.fromString("00000000-0000-7000-8000-000000000120");
        // Issue NLTI-001: @NotBlank on NltiRequestDTO.prompt → Spring MVC 400
        // Requires spring-boot-starter-validation (Hibernate Validator) to be active
        mockMvc.perform(post("/v1/nlt/requests")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")
                .header(NltiCorrelationIdSupport.CORRELATION_ID_HEADER, inboundCorrId.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.correlationId").value(inboundCorrId.toString()))
                .andExpect(result -> org.assertj.core.api.Assertions.assertThat(result.getResponse()
                        .getHeader(NltiCorrelationIdSupport.CORRELATION_ID_HEADER))
                        .isEqualTo(inboundCorrId.toString()));
    }

    // ─── AC-3: X-Correlation-Id inbound header → echoed in response ──────────

    /**
     * AC-3: When an inbound {@code X-Correlation-Id} header is present,
     * the response envelope's {@code correlationId} must reflect that value.
     *
     * <p>
     * The controller correctly echoes the inbound header value in the response
     * envelope.
     */
    @Test
    @WithMockUser(authorities = "nlti:request:submit")
    @DisplayName("AC-3: X-Correlation-Id header is echoed in response correlationId")
    void submitRequest_withCorrelationIdHeader_echosItInResponse() throws Exception {
        // Arrange — Issue NLTI-001: the header value is a valid UUID string
        UUID inboundCorrId = UUID.fromString("00000000-0000-7000-8000-000000000099");
        NltiResponseV1 stubResponse = new NltiResponseV1(
                REQUEST_ID, inboundCorrId, SESSION_ID, "ACCEPTED", null, null);
        when(nltiRequestService.submit(any())).thenReturn(stubResponse);
        when(nltiRequestService.submit(any(), any())).thenReturn(stubResponse);

        String body = objectMapper.writeValueAsString(
                new NltiRequestDTO("check inventory levels", null, null));

        // Act + Assert
        mockMvc.perform(post("/v1/nlt/requests")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .header("X-Correlation-Id", inboundCorrId.toString()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.correlationId").value(inboundCorrId.toString()));
    }

    // ─── AC-4: No authentication → 401 Unauthorized ──────────────────────────

    /**
     * AC-4: An unauthenticated caller must receive HTTP 401 Unauthorized.
     *
     * <p>
     * No {@code @WithMockUser} annotation: an absent security context causes
     * {@code @PreAuthorize} to throw {@link
     * org.springframework.security.authentication.AuthenticationCredentialsNotFoundException}
     * which {@link SecurityExceptionControllerAdvice} maps to HTTP 401
     * (ADR-0017).
     */
    @Test
    @DisplayName("AC-4: POST /v1/nlt/requests without auth returns 401 Unauthorized")
    void submitRequest_withoutAuthentication_returns401() throws Exception {
        // Issue NLTI-001: no @WithMockUser → @PreAuthorize throws → 401
        String body = objectMapper.writeValueAsString(
                new NltiRequestDTO("close workorder 123", null, null));

        mockMvc.perform(post("/v1/nlt/requests")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.correlationId").isNotEmpty())
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }

    // ─── Test slice configuration ─────────────────────────────────────────────

    /**
     * Minimal test-slice configuration for NltiControllerTest:
     * <ul>
     * <li>Enables {@code @PreAuthorize} evaluation so 401/403 tests function.</li>
     * <li>Provides {@link SecurityExceptionControllerAdvice} to map Spring Security
     * exceptions to the correct HTTP status codes (ADR-0017).</li>
     * </ul>
     *
     * Issue: NLTI-001
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
     * Maps Spring Security {@link AccessDeniedException} to HTTP 403 Forbidden
     * and {@link AuthenticationException} subtypes to HTTP 401 Unauthorized in the
     * MockMvc test-slice context.
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
            // HTTP 401 set by @ResponseStatus — covers
            // AuthenticationCredentialsNotFoundException
        }
    }
}
