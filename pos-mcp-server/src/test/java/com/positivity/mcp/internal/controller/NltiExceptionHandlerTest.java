package com.positivity.mcp.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.mcp.internal.dto.NltiRequestDTO;
import com.positivity.mcp.internal.exception.RateLimitExceededException;
import com.positivity.mcp.internal.exception.SessionOwnershipViolationException;
import com.positivity.mcp.service.NltiRequestService;

import jakarta.validation.ConstraintViolationException;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

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
 * Tests for {@link NltiExceptionHandler} exception mapping paths.
 *
 * <p>
 * Uses a {@code @WebMvcTest} slice with the service mocked to throw specific
 * exceptions, verifying that {@link NltiExceptionHandler} maps each exception
 * to the correct HTTP status code and error body.
 *
 * Issue: NLTI-001
 */
@WebMvcTest(NltiController.class)
@ActiveProfiles("test")
class NltiExceptionHandlerTest {
    private static final UUID INBOUND_CORRELATION_ID =
            UUID.fromString("00000000-0000-7000-8000-000000000120");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private NltiRequestService nltiRequestService;

    private String validRequestBody() throws Exception {
        return objectMapper.writeValueAsString(new NltiRequestDTO("test prompt for handler", null, null));
    }

    // ─── RateLimitExceededException → 429 TOO_MANY_REQUESTS ─────────────────

    @Test
    @WithMockUser(authorities = "nlti:request:submit")
    @DisplayName("RateLimitExceededException from service → 429 with RATE_LIMIT_EXCEEDED code")
    void submitRequest_whenRateLimitExceeded_returns429WithRateLimitExceededCode() throws Exception {
        when(nltiRequestService.submit(any(), any()))
                .thenThrow(new RateLimitExceededException("Rate limit exceeded for session: test-session"));

        mockMvc.perform(post("/v1/nlt/requests")
                .contentType(MediaType.APPLICATION_JSON)
                .content(validRequestBody())
                .header(NltiCorrelationIdSupport.CORRELATION_ID_HEADER, INBOUND_CORRELATION_ID.toString()))
                .andExpect(status().is(HttpStatus.TOO_MANY_REQUESTS.value()))
                .andExpect(jsonPath("$.status").isNumber())
                .andExpect(jsonPath("$.code").value("RATE_LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$.correlationId").value(INBOUND_CORRELATION_ID.toString()))
                .andExpect(result -> assertThat(result.getResponse()
                        .getHeader(NltiCorrelationIdSupport.CORRELATION_ID_HEADER))
                        .isEqualTo(INBOUND_CORRELATION_ID.toString()));
    }

    @Test
    @WithMockUser(authorities = "nlti:request:submit")
    @DisplayName("invalid inbound X-Correlation-Id + service error → response uses controller-resolved UUID")
    void submitRequest_whenCorrelationHeaderInvalidAndServiceThrows_usesControllerResolvedCorrelationId() throws Exception {
        AtomicReference<UUID> capturedCorrelationId = new AtomicReference<>();
        when(nltiRequestService.submit(any(), any()))
                .thenAnswer(invocation -> {
                    capturedCorrelationId.set(invocation.getArgument(1, UUID.class));
                    throw new RateLimitExceededException("Rate limit exceeded for session: test-session");
                });

        mockMvc.perform(post("/v1/nlt/requests")
                .contentType(MediaType.APPLICATION_JSON)
                .content(validRequestBody())
                .header(NltiCorrelationIdSupport.CORRELATION_ID_HEADER, "not-a-uuid"))
                .andExpect(status().is(HttpStatus.TOO_MANY_REQUESTS.value()))
                .andExpect(jsonPath("$.status").isNumber())
                .andExpect(jsonPath("$.code").value("RATE_LIMIT_EXCEEDED"))
                .andExpect(result -> {
                    String responseBody = result.getResponse().getContentAsString();
                    String responseCorrelationId = objectMapper.readTree(responseBody).path("correlationId").asText();
                    assertThat(capturedCorrelationId.get()).isNotNull();
                    assertThat(responseCorrelationId).isEqualTo(capturedCorrelationId.get().toString());
                    assertThat(result.getResponse().getHeader(NltiCorrelationIdSupport.CORRELATION_ID_HEADER))
                            .isEqualTo(capturedCorrelationId.get().toString());
                });
    }

    @Test
    @WithMockUser(authorities = "nlti:request:submit")
    @DisplayName("SessionOwnershipViolationException from service → 403 with SESSION_ACCESS_DENIED code")
    void submitRequest_whenSessionOwnershipViolation_returns403WithSessionAccessDeniedCode() throws Exception {
        when(nltiRequestService.submit(any(), any()))
                .thenThrow(new SessionOwnershipViolationException("Provided sessionId is not owned by subject"));

        mockMvc.perform(post("/v1/nlt/requests")
                .contentType(MediaType.APPLICATION_JSON)
                .content(validRequestBody()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").isNumber())
                .andExpect(jsonPath("$.code").value("SESSION_ACCESS_DENIED"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }

    // ─── UnsupportedOperationException → 501 NOT_IMPLEMENTED ─────────────────

    @Test
    @WithMockUser(authorities = "nlti:request:submit")
    @DisplayName("UnsupportedOperationException from service → 501 with NOT_IMPLEMENTED code")
    void submitRequest_whenUnsupportedOperation_returns501WithNotImplementedCode() throws Exception {
        when(nltiRequestService.submit(any(), any()))
                .thenThrow(new UnsupportedOperationException("feature not yet implemented"));

        mockMvc.perform(post("/v1/nlt/requests")
                .contentType(MediaType.APPLICATION_JSON)
                .content(validRequestBody()))
                .andExpect(status().is(HttpStatus.NOT_IMPLEMENTED.value()))
                .andExpect(jsonPath("$.status").isNumber())
                .andExpect(jsonPath("$.code").value("NOT_IMPLEMENTED"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }

    // ─── ConstraintViolationException → 400 BAD_REQUEST ─────────────────────

    @Test
    @WithMockUser(authorities = "nlti:request:submit")
    @DisplayName("ConstraintViolationException from service → 400 with VALIDATION_ERROR code")
    void submitRequest_whenConstraintViolation_returns400WithValidationErrorCode() throws Exception {
        when(nltiRequestService.submit(any(), any()))
                .thenThrow(new ConstraintViolationException("field must not be blank", Set.of()));

        mockMvc.perform(post("/v1/nlt/requests")
                .contentType(MediaType.APPLICATION_JSON)
                .content(validRequestBody()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").isNumber())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }

    // ─── Test slice configuration ─────────────────────────────────────────────

    @TestConfiguration
    @EnableMethodSecurity(prePostEnabled = true)
    static class SliceTestConfig {

        @Bean
        SecurityExceptionHandlerAdvice securityExceptionHandlerAdvice() {
            return new SecurityExceptionHandlerAdvice();
        }
    }

    @ControllerAdvice
    static class SecurityExceptionHandlerAdvice {

        @ExceptionHandler(AccessDeniedException.class)
        @ResponseStatus(HttpStatus.FORBIDDEN)
        void handleAccessDenied() {
        }

        @ExceptionHandler(AuthenticationException.class)
        @ResponseStatus(HttpStatus.UNAUTHORIZED)
        void handleAuthenticationException() {
        }
    }
}
