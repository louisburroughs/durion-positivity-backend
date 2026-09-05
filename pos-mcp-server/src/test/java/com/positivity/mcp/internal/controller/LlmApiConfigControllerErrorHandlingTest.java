package com.positivity.mcp.internal.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.mcp.internal.exception.LlmApiIdAlreadyExistsException;
import com.positivity.mcp.internal.service.LlmApiConfigService;
import com.positivity.web.common.WebCommonErrorAutoConfiguration;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Issue #1713 (part 2): {@link LlmApiConfigController} had no {@code @RestControllerAdvice} at
 * all, so a duplicate {@code apiId} — a stateful collision against existing data, which
 * ADR-0017 §2 makes a 409 — reached pos-web-common's platform fallback and answered
 * {@code 500 INTERNAL_ERROR}. The same gap turned an unknown configuration id into a 500
 * instead of a 404. Both were documented-but-wrong contracts: the controller's own
 * {@code @Operation} prose admitted "this module surfaces that failure as a 500".
 */
@WebMvcTest(LlmApiConfigController.class)
@Import(WebCommonErrorAutoConfiguration.class)
@ActiveProfiles("test")
@DisplayName("LLM API config endpoints answer the errors they document (#1713)")
class LlmApiConfigControllerErrorHandlingTest {

    // Hardcoded test UUID — no UUID.randomUUID() per ADR-0013
    private static final UUID CONFIG_ID = UUID.fromString("00000000-0000-7000-8000-000000000501");

    private static final String BODY = """
            {"apiId":"openai-gpt4o",
             "model":"gpt-4o",
             "baseUrl":"https://api.openai.com/v1",
             "apiKey":"sk-proj-xxxxxxxxxxxxxxxx"}
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private LlmApiConfigService service;

    @Test
    @WithMockUser(authorities = "mcp:llm_api:create")
    @DisplayName("a duplicate apiId answers 409, not 500")
    void duplicateApiIdAnswersConflict() throws Exception {
        when(service.create(any()))
                .thenThrow(new LlmApiIdAlreadyExistsException("LLM API id already exists: openai-gpt4o"));

        mockMvc.perform(post("/v1/llm-apis")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LLM_API_ID_CONFLICT"))
                .andExpect(jsonPath("$.message").value("LLM API id already exists: openai-gpt4o"))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.correlationId").isNotEmpty())
                .andExpect(header().exists("X-Correlation-Id"));
    }

    @Test
    @WithMockUser(authorities = "mcp:llm_api:view")
    @DisplayName("an unknown configuration id answers 404, not 500")
    void unknownIdAnswersNotFound() throws Exception {
        when(service.get(CONFIG_ID)).thenThrow(new NoSuchElementException("LLM API config not found: " + CONFIG_ID));

        mockMvc.perform(get("/v1/llm-apis/{id}", CONFIG_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LLM_API_NOT_FOUND"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.correlationId").isNotEmpty())
                .andExpect(header().exists("X-Correlation-Id"));
    }
}
