package com.positivity.mcp.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.mcp.internal.security.McpPermissions;
import com.positivity.mcp.service.DocumentIngestionJob;
import com.positivity.mcp.service.DocumentIngestionJobStatus;
import com.positivity.mcp.service.DocumentIngestionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.net.URI;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth", scopes = { "mcp:document:ingest" })
@RequestMapping("/v1/mcp")
@Tag(name = "Document Ingestion", description = "RAG document ingestion job management")
public class DocumentIngestionController {

    private final DocumentIngestionService documentIngestionService;

    public DocumentIngestionController(@NonNull DocumentIngestionService documentIngestionService) {
        this.documentIngestionService = documentIngestionService;
    }

    @PostMapping("/documents")
    @PreAuthorize("hasAuthority('" + McpPermissions.MCP_DOCUMENT_INGEST + "')")
    @EmitEvent(id = "MCP_DOCUMENT_INGEST", apiVersion = "1")
    @Operation(summary = "Queue a document for ingestion into the RAG vector store", description = "Submit a document for asynchronous ingestion into the RAG vector store. The request includes the document content and optional metadata. The response returns a job ID that can be used to track the ingestion status.", tags = {
            "Document Ingestion" })
    public ResponseEntity<DocumentIngestionJobResponse> ingestDocument(
            @RequestBody @Valid @NonNull DocumentIngestionRequest request) {
        DocumentIngestionJob job = documentIngestionService.submitDocument(request.content(), request.metadata());
        URI location = URI.create("/v1/mcp/documents/jobs/" + job.jobId());
        return ResponseEntity.accepted().location(location).body(DocumentIngestionJobResponse.from(job));
    }

    @GetMapping("/documents/jobs/{jobId}")
    @PreAuthorize("hasAuthority('" + McpPermissions.MCP_DOCUMENT_INGEST + "')")
    @Operation(summary = "Get RAG document ingestion job status", description = "Retrieve the status and details of an asynchronous document ingestion job using its job ID. The response includes the current status, timestamps, and any error messages if applicable.", tags = {
            "Document Ingestion" })
    public ResponseEntity<DocumentIngestionJobResponse> getIngestionJob(@PathVariable @NonNull UUID jobId) {
        return documentIngestionService
                .getIngestionJob(jobId)
                .map(DocumentIngestionJobResponse::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @Schema(name = "DocumentIngestionRequest", description = "Document ingestion payload", example = "{\"content\":\"sample\",\"metadata\":{\"document_id\":\"policy-123\",\"source\":\"manual\"}}")
    public record DocumentIngestionRequest(
            @NotBlank @NonNull String content,

            @Schema(description = "Optional key-value metadata for the document") Map<String, Object> metadata) {
        public DocumentIngestionRequest {
            if (metadata == null) {
                metadata = Map.of();
            }
        }
    }

    @Schema(name = "DocumentIngestionJobResponse", description = "Asynchronous document ingestion job status")
    public record DocumentIngestionJobResponse(
            @NonNull UUID jobId,
            @NonNull String documentId,
            @NonNull DocumentIngestionJobStatus status,
            @NonNull OffsetDateTime createdAt,
            @NonNull OffsetDateTime updatedAt,
            OffsetDateTime startedAt,
            OffsetDateTime completedAt,
            Integer chunkCount,
            String errorMessage) {

        static @NonNull DocumentIngestionJobResponse from(@NonNull DocumentIngestionJob job) {
            return new DocumentIngestionJobResponse(
                    job.jobId(),
                    job.documentId(),
                    job.status(),
                    job.createdAt(),
                    job.updatedAt(),
                    job.startedAt(),
                    job.completedAt(),
                    job.chunkCount(),
                    job.errorMessage());
        }
    }
}
