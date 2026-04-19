package com.positivity.mcp.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.mcp.internal.security.McpPermissions;
import com.positivity.mcp.service.DocumentIngestionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.net.URI;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/mcp")
public class DocumentIngestionController {

    private final DocumentIngestionService documentIngestionService;

    public DocumentIngestionController(@NonNull DocumentIngestionService documentIngestionService) {
        this.documentIngestionService = documentIngestionService;
    }

    @PostMapping("/documents")
    @PreAuthorize("hasAuthority('" + McpPermissions.MCP_DOCUMENT_INGEST + "')")
    @EmitEvent(id = "MCP_DOCUMENT_INGEST", apiVersion = "1")
    @Operation(summary = "Ingest a document into the RAG vector store")
    public ResponseEntity<Void> ingestDocument(@RequestBody @Valid @NonNull DocumentIngestionRequest request) {
        documentIngestionService.ingestDocument(request.content(), request.metadata());
        return ResponseEntity.created(URI.create("/v1/mcp/documents")).build();
    }

    @Schema(
            name = "DocumentIngestionRequest",
            description = "Document ingestion payload",
            example = "{\"content\":\"sample\",\"metadata\":{\"document_id\":\"policy-123\",\"source\":\"manual\"}}")
    public record DocumentIngestionRequest(
            @NotBlank @NonNull String content,

            @Schema(description = "Optional key-value metadata for the document")
            Map<String, Object> metadata) {
        public DocumentIngestionRequest {
            if (metadata == null) {
                metadata = Map.of();
            }
        }
    }
}
