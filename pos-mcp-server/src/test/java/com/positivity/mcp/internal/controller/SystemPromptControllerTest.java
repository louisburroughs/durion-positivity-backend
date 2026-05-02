package com.positivity.mcp.internal.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.mcp.internal.dto.SystemPromptRequest;
import com.positivity.mcp.internal.dto.SystemPromptResponse;
import com.positivity.mcp.service.SystemPromptService;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.NoSuchElementException;
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
 * Controller slice tests for SystemPromptController.
 *
 * <p>
 * Verifies that {@link SystemPromptController}:
 * <ul>
 * <li>Returns HTTP 200 (list/get), 201 (create), 200 (update), 204 (delete)
 * for callers with appropriate {@code mcp:system_prompt:*} authority.</li>
 * <li>Returns HTTP 400 for requests that fail Bean Validation.</li>
 * <li>Returns HTTP 403 for callers missing the required authority.</li>
 * <li>Returns HTTP 401 for unauthenticated callers (ADR-0017).</li>
 * </ul>
 */
@WebMvcTest(SystemPromptController.class)
@ActiveProfiles("test")
class SystemPromptControllerTest {

    // Hardcoded test UUIDs — no UUID.randomUUID() per ADR-0013
    private static final UUID PROMPT_ID = UUID.fromString("00000000-0000-7000-8000-000000000401");
    private static final OffsetDateTime FIXED_TIME = OffsetDateTime.parse("2026-01-01T12:00:00+00:00");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private SystemPromptService systemPromptService;

    private SystemPromptResponse stubResponse() {
        return new SystemPromptResponse(PROMPT_ID, "default", "You are a helpful assistant.", FIXED_TIME, FIXED_TIME);
    }

    // ─── AC: GET /v1/prompts → 200 with list ─────────────────────────────────

    /**
     * Authenticated caller with {@code mcp:system_prompt:view} must receive
     * HTTP 200 with the list of system prompts.
     */
    @Test
    @WithMockUser(authorities = "mcp:system_prompt:view")
    @DisplayName("GET /v1/prompts with view authority → 200 with list")
    void list_withViewAuthority_returns200WithList() throws Exception {
        when(systemPromptService.findAll()).thenReturn(List.of(stubResponse()));

        mockMvc.perform(get("/v1/prompts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(PROMPT_ID.toString()));
    }

    // ─── AC: GET /v1/prompts/{id} → 200 ──────────────────────────────────────

    /**
     * Authenticated caller with {@code mcp:system_prompt:view} getting a single
     * prompt by ID must receive HTTP 200 with the prompt body.
     */
    @Test
    @WithMockUser(authorities = "mcp:system_prompt:view")
    @DisplayName("GET /v1/prompts/{id} with view authority → 200 with prompt")
    void get_withViewAuthority_returns200WithPrompt() throws Exception {
        when(systemPromptService.get(PROMPT_ID)).thenReturn(stubResponse());

        mockMvc.perform(get("/v1/prompts/{id}", PROMPT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(PROMPT_ID.toString()))
                .andExpect(jsonPath("$.name").value("default"));
    }

    // ─── AC: POST /v1/prompts → 201 Created ──────────────────────────────────

    /**
     * Authenticated caller with {@code mcp:system_prompt:create} posting a
     * valid request must receive HTTP 201 Created with the created prompt body.
     */
    @Test
    @WithMockUser(authorities = "mcp:system_prompt:create")
    @DisplayName("POST /v1/prompts with valid request → 201 Created")
    void create_withValidRequest_returns201() throws Exception {
        when(systemPromptService.create(any())).thenReturn(stubResponse());

        SystemPromptRequest request = new SystemPromptRequest("default", "You are a helpful assistant.");

        mockMvc.perform(post("/v1/prompts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(PROMPT_ID.toString()));
    }

    // ─── AC: POST /v1/prompts with blank fields → 400 ────────────────────────

    /**
     * A {@code POST} with blank required fields must be rejected with HTTP 400
     * via Bean Validation ({@code @NotBlank}).
     */
    @Test
    @WithMockUser(authorities = "mcp:system_prompt:create")
    @DisplayName("POST /v1/prompts with blank fields → 400 Bad Request")
    void create_withBlankFields_returns400() throws Exception {
        mockMvc.perform(post("/v1/prompts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(HttpStatus.BAD_REQUEST.value()))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("Request validation failed"))
                .andExpect(jsonPath("$.fieldErrors").isArray())
                .andExpect(jsonPath("$.fieldErrors[0].field").exists())
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }

    // ─── AC: PUT /v1/prompts/{id} → 200 ──────────────────────────────────────

    /**
     * Authenticated caller with {@code mcp:system_prompt:update} posting a
     * valid update must receive HTTP 200 with the updated prompt body.
     */
    @Test
    @WithMockUser(authorities = "mcp:system_prompt:update")
    @DisplayName("PUT /v1/prompts/{id} with valid request → 200 OK")
    void update_withValidRequest_returns200() throws Exception {
        when(systemPromptService.update(eq(PROMPT_ID), any())).thenReturn(stubResponse());

        SystemPromptRequest request = new SystemPromptRequest("default", "Updated system prompt.");

        mockMvc.perform(put("/v1/prompts/{id}", PROMPT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(PROMPT_ID.toString()));
    }

    @Test
    @WithMockUser(authorities = "mcp:system_prompt:create")
    @DisplayName("POST /v1/prompts with duplicate name → 400 ApiError")
    void create_withDuplicateName_returns400ApiError() throws Exception {
        when(systemPromptService.create(any())).thenThrow(new IllegalArgumentException("Prompt with name already exists: default"));

        SystemPromptRequest request = new SystemPromptRequest("default", "You are a helpful assistant.");

        mockMvc.perform(post("/v1/prompts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(HttpStatus.BAD_REQUEST.value()))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("Prompt with name already exists: default"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }

    @Test
    @WithMockUser(authorities = "mcp:system_prompt:view")
    @DisplayName("GET /v1/prompts/{id} missing prompt → 404 ApiError")
    void get_whenPromptMissing_returns404ApiError() throws Exception {
        when(systemPromptService.get(PROMPT_ID)).thenThrow(new NoSuchElementException("Prompt not found: " + PROMPT_ID));

        mockMvc.perform(get("/v1/prompts/{id}", PROMPT_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(HttpStatus.NOT_FOUND.value()))
                .andExpect(jsonPath("$.code").value("SYSTEM_PROMPT_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Prompt not found: " + PROMPT_ID))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }

    // ─── AC: DELETE /v1/prompts/{id} → 204 No Content ────────────────────────

    /**
     * Authenticated caller with {@code mcp:system_prompt:delete} must receive
     * HTTP 204 No Content and the service delete must be invoked.
     */
    @Test
    @WithMockUser(authorities = "mcp:system_prompt:delete")
    @DisplayName("DELETE /v1/prompts/{id} with delete authority → 204 No Content")
    void delete_withDeleteAuthority_returns204() throws Exception {
        mockMvc.perform(delete("/v1/prompts/{id}", PROMPT_ID)).andExpect(status().isNoContent());

        verify(systemPromptService).delete(PROMPT_ID);
    }

    // ─── ADR-0017: wrong authority → 403 ────────────────────────────────────

    /**
     * A caller authenticated but missing {@code mcp:system_prompt:view} must
     * receive HTTP 403 Forbidden.
     */
    @Test
    @WithMockUser(authorities = "other:authority")
    @DisplayName("GET /v1/prompts without view authority → 403 Forbidden")
    void list_withoutViewAuthority_returns403() throws Exception {
        mockMvc.perform(get("/v1/prompts")).andExpect(status().isForbidden());
    }

    // ─── ADR-0017: no authentication → 401 ──────────────────────────────────

    /**
     * An unauthenticated caller must receive HTTP 401 Unauthorized (ADR-0017).
     */
    @Test
    @DisplayName("GET /v1/prompts unauthenticated → 401 Unauthorized")
    void list_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/v1/prompts")).andExpect(status().isUnauthorized());
    }

    // ─── Test slice configuration ────────────────────────────────────────────

    /**
     * Minimal test-slice configuration:
     * <ul>
     * <li>Enables {@code @PreAuthorize} evaluation so 401/403 tests function.</li>
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
