package com.positivity.mcp.internal.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.mcp.service.DocumentIngestionJob;
import com.positivity.mcp.service.DocumentIngestionJobStatus;
import com.positivity.mcp.service.DocumentIngestionService;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(DocumentIngestionController.class)
@ActiveProfiles("test")
class DocumentIngestionControllerTest {

    private static final UUID JOB_ID = UUID.fromString("018f0000-0000-7000-8000-000000000001");
    private static final OffsetDateTime CREATED_AT = OffsetDateTime.parse("2026-04-19T12:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private DocumentIngestionService documentIngestionService;

    @Test
    @WithMockUser(authorities = "mcp:document:ingest")
    @DisplayName("POST /v1/mcp/documents with valid payload returns 202 Accepted and job status")
    void ingestDocument_withValidPayload_returns202() throws Exception {
        var payload = Map.of(
                "content", "Vehicle inspection checklist", "metadata", Map.of("source", "manual", "type", "checklist"));
        when(documentIngestionService.submitDocument(
                        eq("Vehicle inspection checklist"), eq(Map.of("source", "manual", "type", "checklist"))))
                .thenReturn(job(DocumentIngestionJobStatus.PENDING, null, null));

        mockMvc.perform(post("/v1/mcp/documents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", "/v1/mcp/documents/jobs/" + JOB_ID))
                .andExpect(jsonPath("$.jobId").value(JOB_ID.toString()))
                .andExpect(jsonPath("$.documentId").value("doc-123"))
                .andExpect(jsonPath("$.status").value("PENDING"));

        verify(documentIngestionService)
                .submitDocument("Vehicle inspection checklist", Map.of("source", "manual", "type", "checklist"));
    }

    @Test
    @WithMockUser(authorities = "mcp:document:ingest")
    @DisplayName("POST /v1/mcp/documents with null metadata delegates empty map")
    void ingestDocument_withNullMetadata_delegatesEmptyMap() throws Exception {
        when(documentIngestionService.submitDocument(eq("Only content provided"), eq(Map.of())))
                .thenReturn(job(DocumentIngestionJobStatus.PENDING, null, null));

        mockMvc.perform(post("/v1/mcp/documents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"Only content provided\",\"metadata\":null}"))
                .andExpect(status().isAccepted());

        verify(documentIngestionService).submitDocument("Only content provided", Map.of());
    }

    @Test
    @WithMockUser(authorities = "mcp:document:ingest")
    @DisplayName("POST /v1/mcp/documents with blank content returns 400")
    void ingestDocument_withBlankContent_returns400() throws Exception {
        mockMvc.perform(post("/v1/mcp/documents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(authorities = "mcp:document:ingest")
    @DisplayName("GET /v1/mcp/documents/jobs/{jobId} returns job status")
    void getIngestionJob_whenFound_returnsStatus() throws Exception {
        when(documentIngestionService.getIngestionJob(JOB_ID))
                .thenReturn(Optional.of(job(DocumentIngestionJobStatus.SUCCEEDED, 3, null)));

        mockMvc.perform(get("/v1/mcp/documents/jobs/{jobId}", JOB_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").value(JOB_ID.toString()))
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.chunkCount").value(3));
    }

    @Test
    @WithMockUser(authorities = "mcp:document:ingest")
    @DisplayName("GET /v1/mcp/documents/jobs/{jobId} returns 404 when missing")
    void getIngestionJob_whenMissing_returns404() throws Exception {
        when(documentIngestionService.getIngestionJob(any(UUID.class))).thenReturn(Optional.empty());

        mockMvc.perform(get("/v1/mcp/documents/jobs/{jobId}", JOB_ID)).andExpect(status().isNotFound());
    }

    private static DocumentIngestionJob job(
            DocumentIngestionJobStatus status, Integer chunkCount, String errorMessage) {
        return new DocumentIngestionJob(
                JOB_ID,
                "doc-123",
                status,
                CREATED_AT,
                CREATED_AT,
                status == DocumentIngestionJobStatus.PENDING ? null : CREATED_AT,
                status == DocumentIngestionJobStatus.PENDING || status == DocumentIngestionJobStatus.RUNNING
                        ? null
                        : CREATED_AT,
                chunkCount,
                errorMessage);
    }
}
