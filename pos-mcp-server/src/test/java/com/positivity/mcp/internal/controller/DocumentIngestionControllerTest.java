package com.positivity.mcp.internal.controller;

import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.mcp.service.DocumentIngestionService;
import java.util.Map;
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

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private DocumentIngestionService documentIngestionService;

    @Test
    @WithMockUser(authorities = "mcp:document:ingest")
    @DisplayName("POST /v1/mcp/documents with valid payload returns 201 Created")
    void ingestDocument_withValidPayload_returns201() throws Exception {
        var payload = Map.of(
                "content", "Vehicle inspection checklist", "metadata", Map.of("source", "manual", "type", "checklist"));

        mockMvc.perform(post("/v1/mcp/documents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/v1/mcp/documents"));

        verify(documentIngestionService)
                .ingestDocument("Vehicle inspection checklist", Map.of("source", "manual", "type", "checklist"));
    }

    @Test
    @WithMockUser(authorities = "mcp:document:ingest")
    @DisplayName("POST /v1/mcp/documents with null metadata delegates empty map")
    void ingestDocument_withNullMetadata_delegatesEmptyMap() throws Exception {
        mockMvc.perform(post("/v1/mcp/documents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"Only content provided\",\"metadata\":null}"))
                .andExpect(status().isCreated());

        verify(documentIngestionService).ingestDocument("Only content provided", Map.of());
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
}
