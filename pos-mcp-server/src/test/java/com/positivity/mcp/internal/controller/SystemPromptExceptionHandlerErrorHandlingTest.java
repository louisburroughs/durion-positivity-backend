package com.positivity.mcp.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.mcp.internal.dto.SystemPromptRequest;
import com.positivity.mcp.internal.exception.SystemPromptNameConflictException;
import com.positivity.mcp.internal.service.SystemPromptService;
import com.positivity.web.common.WebCommonErrorAutoConfiguration;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * End-to-end proof for issue #1694, exercised through {@link SystemPromptController}:
 * {@link SystemPromptNameConflictException} keeps a documented, specific contract (409 {@code
 * SYSTEM_PROMPT_NAME_CONFLICT}, message echoed), while a bare {@code IllegalArgumentException} --
 * what Hibernate/JPA throw for an invalid query, what {@code UUID.fromString} throws on malformed
 * stored data -- is no longer caught by {@link SystemPromptExceptionHandler}: it falls through to
 * {@code pos-web-common}'s platform-wide {@code GlobalApiExceptionHandler}, which answers a
 * generic, correlated 500 that never echoes the exception's own text.
 *
 * <p>{@code @WebMvcTest} does not auto-register {@code pos-web-common}'s {@code @AutoConfiguration},
 * so {@link WebCommonErrorAutoConfiguration} is imported explicitly here to exercise the real
 * fallback chain rather than asserting a weaker substitute.
 */
@WebMvcTest(SystemPromptController.class)
@Import(WebCommonErrorAutoConfiguration.class)
class SystemPromptExceptionHandlerErrorHandlingTest {

    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2026-09-03T12:00:00Z"), ZoneOffset.UTC);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private SystemPromptService systemPromptService;

    @Test
    @WithMockUser(authorities = "mcp:system_prompt:create")
    void aNameConflictAnswers409WithItsOwnMessageAndCode() throws Exception {
        when(systemPromptService.create(any()))
                .thenThrow(new SystemPromptNameConflictException("Prompt with name already exists: default"));

        mockMvc.perform(post("/v1/prompts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new SystemPromptRequest("default", "You are a helpful assistant."))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SYSTEM_PROMPT_NAME_CONFLICT"))
                .andExpect(jsonPath("$.message").value("Prompt with name already exists: default"));
    }

    /**
     * The regression this test guards against (#1694): a bare {@code IllegalArgumentException}
     * must NOT come back as a 400 carrying its own message via a blanket handler. It is an
     * unexpected server-side failure, so it must land on the generic, correlated 500 fallback.
     */
    @Test
    @WithMockUser(authorities = "mcp:system_prompt:create")
    void anUnexpectedIllegalArgumentExceptionAnswers500WithoutLeakingItsMessage() throws Exception {
        String leakCanary = "org.hibernate.query.sqm.UnknownPathException: Could not resolve attribute 'promptName'"
                + " of 'com.positivity.mcp.internal.entity.SystemPrompt'";
        when(systemPromptService.create(any())).thenThrow(new IllegalArgumentException(leakCanary));

        String body = mockMvc.perform(post("/v1/prompts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new SystemPromptRequest("default", "You are a helpful assistant."))))
                .andExpect(status().isInternalServerError())
                .andExpect(header().exists("X-Correlation-Id"))
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("Unexpected error occurred"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body)
                .doesNotContain(leakCanary)
                .doesNotContain("UnknownPathException")
                .doesNotContain("promptName");
    }

    @TestConfiguration
    @EnableMethodSecurity(prePostEnabled = true)
    static class SliceTestConfig {

        /** Required by pos-web-common's {@code GlobalApiExceptionHandler}. */
        @Bean
        Clock clock() {
            return TEST_CLOCK;
        }
    }
}
