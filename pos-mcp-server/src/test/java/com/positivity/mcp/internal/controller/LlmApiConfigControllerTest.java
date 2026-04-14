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
import com.positivity.mcp.internal.dto.LlmApiConfigRequest;
import com.positivity.mcp.internal.dto.LlmApiConfigResponse;
import com.positivity.mcp.service.LlmApiConfigService;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
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
 * Controller slice tests for LlmApiConfigController.
 *
 * <p>
 * Verifies that {@link LlmApiConfigController}:
 * <ul>
 * <li>Returns HTTP 200 (list/get) and 201 (create) for callers with the
 * appropriate {@code mcp:llm_api:*} authority.</li>
 * <li>Returns HTTP 400 for requests that fail Bean Validation.</li>
 * <li>Returns HTTP 403 for callers missing the required authority.</li>
 * <li>Returns HTTP 401 for unauthenticated callers (ADR-0017).</li>
 * </ul>
 */
@WebMvcTest(LlmApiConfigController.class)
@ActiveProfiles("test")
class LlmApiConfigControllerTest {

    // Hardcoded test UUIDs — no UUID.randomUUID() per ADR-0013
    private static final UUID CONFIG_ID = UUID.fromString("00000000-0000-7000-8000-000000000301");
    private static final OffsetDateTime FIXED_TIME = OffsetDateTime.parse("2026-01-01T12:00:00+00:00");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private LlmApiConfigService llmApiConfigService;

    private LlmApiConfigResponse stubResponse() {
        return new LlmApiConfigResponse(
                CONFIG_ID,
                "test-api",
                "gpt-4",
                "http://localhost:9999",
                "secret-key",
                Map.of(),
                FIXED_TIME,
                FIXED_TIME);
    }

    // ─── AC: GET /v1/llm-apis → 200 with list ────────────────────────────────

    /**
     * Authenticated caller with {@code mcp:llm_api:view} must receive HTTP 200
     * with the list of LLM API configs.
     */
    @Test
    @WithMockUser(authorities = "mcp:llm_api:view")
    @DisplayName("GET /v1/llm-apis with view authority → 200 with list")
    void list_withViewAuthority_returns200WithList() throws Exception {
        when(llmApiConfigService.list()).thenReturn(List.of(stubResponse()));

        mockMvc.perform(get("/v1/llm-apis"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(CONFIG_ID.toString()));
    }

    // ─── AC: GET /v1/llm-apis/{id} → 200 ────────────────────────────────────

    /**
     * Authenticated caller with {@code mcp:llm_api:view} receiving a single
     * config by ID must get HTTP 200 with the config body.
     */
    @Test
    @WithMockUser(authorities = "mcp:llm_api:view")
    @DisplayName("GET /v1/llm-apis/{id} with view authority → 200 with config")
    void get_withViewAuthority_returns200WithConfig() throws Exception {
        when(llmApiConfigService.get(CONFIG_ID)).thenReturn(stubResponse());

        mockMvc.perform(get("/v1/llm-apis/{id}", CONFIG_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(CONFIG_ID.toString()))
                .andExpect(jsonPath("$.apiId").value("test-api"));
    }

    // ─── AC: POST /v1/llm-apis → 201 Created ────────────────────────────────

    /**
     * Authenticated caller with {@code mcp:llm_api:create} posting a valid
     * request must receive HTTP 201 Created with the created config body.
     */
    @Test
    @WithMockUser(authorities = "mcp:llm_api:create")
    @DisplayName("POST /v1/llm-apis with valid request → 201 Created")
    void create_withValidRequest_returns201() throws Exception {
        when(llmApiConfigService.create(any())).thenReturn(stubResponse());

        LlmApiConfigRequest request =
                new LlmApiConfigRequest("test-api", "gpt-4", "http://localhost:9999", "secret-key", null);

        mockMvc.perform(post("/v1/llm-apis")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(CONFIG_ID.toString()));
    }

    // ─── AC: POST /v1/llm-apis with blank fields → 400 ──────────────────────

    /**
     * A {@code POST} with blank required fields must be rejected with HTTP 400
     * via Bean Validation ({@code @NotBlank}).
     */
    @Test
    @WithMockUser(authorities = "mcp:llm_api:create")
    @DisplayName("POST /v1/llm-apis with blank fields → 400 Bad Request")
    void create_withBlankFields_returns400() throws Exception {
        mockMvc.perform(post("/v1/llm-apis")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    // ─── AC: PUT /v1/llm-apis/{id} → 200 ────────────────────────────────────

    /**
     * Authenticated caller with {@code mcp:llm_api:update} posting a valid
     * update must receive HTTP 200 with the updated config body.
     */
    @Test
    @WithMockUser(authorities = "mcp:llm_api:update")
    @DisplayName("PUT /v1/llm-apis/{id} with valid request → 200 OK")
    void update_withValidRequest_returns200() throws Exception {
        when(llmApiConfigService.update(eq(CONFIG_ID), any())).thenReturn(stubResponse());

        LlmApiConfigRequest request =
                new LlmApiConfigRequest("test-api", "gpt-4", "http://localhost:9999", "new-key", null);

        mockMvc.perform(put("/v1/llm-apis/{id}", CONFIG_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(CONFIG_ID.toString()));
    }

    // ─── AC: DELETE /v1/llm-apis/{id} → 204 No Content ───────────────────────

    /**
     * Authenticated caller with {@code mcp:llm_api:delete} must receive HTTP
     * 204 No Content and the service delete must be invoked.
     */
    @Test
    @WithMockUser(authorities = "mcp:llm_api:delete")
    @DisplayName("DELETE /v1/llm-apis/{id} with delete authority → 204 No Content")
    void delete_withDeleteAuthority_returns204() throws Exception {
        mockMvc.perform(delete("/v1/llm-apis/{id}", CONFIG_ID)).andExpect(status().isNoContent());

        verify(llmApiConfigService).delete(CONFIG_ID);
    }

    // ─── ADR-0017: wrong authority → 403 ────────────────────────────────────

    /**
     * A caller authenticated but missing {@code mcp:llm_api:view} must receive
     * HTTP 403 Forbidden.
     */
    @Test
    @WithMockUser(authorities = "other:authority")
    @DisplayName("GET /v1/llm-apis without view authority → 403 Forbidden")
    void list_withoutViewAuthority_returns403() throws Exception {
        mockMvc.perform(get("/v1/llm-apis")).andExpect(status().isForbidden());
    }

    // ─── ADR-0017: no authentication → 401 ──────────────────────────────────

    /**
     * An unauthenticated caller must receive HTTP 401 Unauthorized (ADR-0017).
     */
    @Test
    @DisplayName("GET /v1/llm-apis unauthenticated → 401 Unauthorized")
    void list_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/v1/llm-apis")).andExpect(status().isUnauthorized());
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
