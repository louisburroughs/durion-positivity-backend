package com.positivity.mcp.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.mcp.internal.config.DocumentIngestionService;
import com.positivity.mcp.internal.exception.InvalidDocumentMetadataException;
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
 * End-to-end proof for issue #1694, exercised through {@link DocumentIngestionController} (one of
 * the four controllers {@link NltiExceptionHandler} covers): {@link InvalidDocumentMetadataException}
 * keeps a documented, specific contract (400 {@code INVALID_DOCUMENT_METADATA}, message echoed),
 * while a bare {@code IllegalArgumentException} -- what Hibernate/JPA throw for an invalid query,
 * what {@code UUID.fromString} throws on malformed stored data -- is no longer caught by {@link
 * NltiExceptionHandler}: it falls through to {@code pos-web-common}'s platform-wide {@code
 * GlobalApiExceptionHandler}, which answers a generic, correlated 500 that never echoes the
 * exception's own text.
 *
 * <p>{@code @WebMvcTest} does not auto-register {@code pos-web-common}'s {@code @AutoConfiguration},
 * so {@link WebCommonErrorAutoConfiguration} is imported explicitly here to exercise the real
 * fallback chain rather than asserting a weaker substitute.
 */
@WebMvcTest(DocumentIngestionController.class)
@Import(WebCommonErrorAutoConfiguration.class)
class NltiExceptionHandlerErrorHandlingTest {

    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2026-09-03T12:00:00Z"), ZoneOffset.UTC);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DocumentIngestionService documentIngestionService;

    @Test
    @WithMockUser(authorities = "mcp:document:ingest")
    void unserializableMetadataAnswers400WithItsOwnMessageAndCode() throws Exception {
        when(documentIngestionService.submitDocument(any(), any()))
                .thenThrow(new InvalidDocumentMetadataException("Document metadata must be JSON-serializable", null));

        mockMvc.perform(post("/v1/mcp/documents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"hello\",\"metadata\":{\"k\":\"v\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_DOCUMENT_METADATA"))
                .andExpect(jsonPath("$.message").value("Document metadata must be JSON-serializable"));
    }

    /**
     * The regression this test guards against (#1694): a bare {@code IllegalArgumentException}
     * must NOT come back as a fabricated client error. It is an unexpected server-side failure, so
     * it must land on the generic, correlated 500 fallback -- and the previous
     * {@code INTERNAL_SERVER_ERROR} code is retired in favor of the canonical {@code INTERNAL_ERROR}
     * (ADR-0056 / docs/ERROR_ENVELOPE.md).
     */
    @Test
    @WithMockUser(authorities = "mcp:document:ingest")
    void anUnexpectedIllegalArgumentExceptionAnswers500WithoutLeakingItsMessage() throws Exception {
        String leakCanary = "org.hibernate.query.sqm.UnknownPathException: Could not resolve attribute 'documentId' of "
                + "'com.positivity.mcp.internal.entity.DocumentIngestionJobEntity'";
        when(documentIngestionService.submitDocument(any(), any())).thenThrow(new IllegalArgumentException(leakCanary));

        String body = mockMvc.perform(post("/v1/mcp/documents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"hello\",\"metadata\":{}}"))
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
                .doesNotContain("documentId");
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
