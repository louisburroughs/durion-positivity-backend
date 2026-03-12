package com.positivity.mcp.internal.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.mcp.internal.dto.NltiRequestDTO;
import com.positivity.mcp.internal.exception.RateLimitExceededException;
import com.positivity.mcp.service.NltiRequestService;

import jakarta.validation.ConstraintViolationException;

import java.util.Set;

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
                .content(validRequestBody()))
                .andExpect(status().is(HttpStatus.TOO_MANY_REQUESTS.value()))
                .andExpect(jsonPath("$.status").value("ERROR"))
                .andExpect(jsonPath("$.code").value("RATE_LIMIT_EXCEEDED"))
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
                .andExpect(jsonPath("$.status").value("ERROR"))
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
                .andExpect(jsonPath("$.status").value("ERROR"))
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
