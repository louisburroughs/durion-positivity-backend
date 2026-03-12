package com.positivity.mcp.internal.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.List;

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
 * Controller slice tests for {@link McpChatController}.
 *
 * <p>
 * Verifies that {@link McpChatController}:
 * <ul>
 * <li>Returns HTTP 200 for authenticated callers with a valid
 * {@code toolName}.</li>
 * <li>Returns HTTP 400 when {@code toolName} is null or blank.</li>
 * <li>Returns HTTP 401 for unauthenticated callers (ADR-0017).</li>
 * </ul>
 */
@WebMvcTest(McpChatController.class)
@ActiveProfiles("test")
class McpChatControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private McpSyncClient mcpSyncClient;

    // ─── AC: POST /v1/mcp/chat with valid toolName → 200 ─────────────────────

    /**
     * Authenticated caller supplying a valid {@code toolName} must receive HTTP
     * 200 OK with the MCP tool result.
     */
    @Test
    @WithMockUser
    @DisplayName("POST /v1/mcp/chat with valid toolName → 200 OK")
    void chat_withValidToolName_returns200() throws Exception {
        McpSchema.CallToolResult result = McpSchema.CallToolResult.builder()
                .content(List.of())
                .isError(false)
                .build();
        when(mcpSyncClient.callTool(any())).thenReturn(result);

        mockMvc.perform(post("/v1/mcp/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"toolName\":\"positivity-ping\",\"arguments\":{}}"))
                .andExpect(status().isOk());
    }

    // ─── AC: POST /v1/mcp/chat with null toolName → 400 ─────────────────────

    /**
     * A request body with no {@code toolName} field (null after deserialization)
     * must be rejected with HTTP 400 by the controller guard.
     */
    @Test
    @WithMockUser
    @DisplayName("POST /v1/mcp/chat with missing toolName → 400 Bad Request")
    void chat_withMissingToolName_returns400() throws Exception {
        mockMvc.perform(post("/v1/mcp/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"arguments\":{}}"))
                .andExpect(status().isBadRequest());
    }

    // ─── AC: POST /v1/mcp/chat with blank toolName → 400 ────────────────────

    /**
     * A request body with a blank {@code toolName} must be rejected with HTTP
     * 400 by the controller's {@code isBlank()} guard.
     */
    @Test
    @WithMockUser
    @DisplayName("POST /v1/mcp/chat with blank toolName → 400 Bad Request")
    void chat_withBlankToolName_returns400() throws Exception {
        mockMvc.perform(post("/v1/mcp/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"toolName\":\"   \",\"arguments\":{}}"))
                .andExpect(status().isBadRequest());
    }

    // ─── ADR-0017: no authentication → 401 ──────────────────────────────────

    /**
     * An unauthenticated caller must receive HTTP 401 Unauthorized (ADR-0017).
     */
    @Test
    @DisplayName("POST /v1/mcp/chat unauthenticated → 401 Unauthorized")
    void chat_unauthenticated_returns401() throws Exception {
        mockMvc.perform(post("/v1/mcp/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"toolName\":\"positivity-ping\",\"arguments\":{}}"))
                .andExpect(status().isUnauthorized());
    }

    // ─── Test slice configuration ────────────────────────────────────────────

    /**
     * Minimal test-slice configuration:
     * <ul>
     * <li>Enables {@code @PreAuthorize} evaluation so 401 tests function.</li>
     * <li>Provides {@link SecurityExceptionControllerAdvice} to map Spring Security
     * exceptions to the correct HTTP status codes (ADR-0017).</li>
     * </ul>
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
     * Maps {@link AccessDeniedException} to HTTP 403 and
     * {@link AuthenticationException}
     * subtypes to HTTP 401 in the MockMvc test-slice context (ADR-0017).
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
