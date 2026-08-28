package com.positivity.securityservice.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.securityservice.internal.dto.AuditExportJobResponse;
import com.positivity.securityservice.internal.dto.AuditExportRequest;
import com.positivity.securityservice.internal.security.SecurityPermissions;
import com.positivity.securityservice.internal.service.AuditExportService;
import com.positivity.shared.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/audit/exports")
@RequiredArgsConstructor
@Tag(name = "Audit Exports", description = "Asynchronous audit export job management")
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
public class AuditExportController {

    private final AuditExportService auditExportService;

    @EmitEvent(id = "SECURITY_AUDIT_EXPORT_REQUEST", apiVersion = "1")
    @PostMapping
    @PreAuthorize("hasAuthority('" + SecurityPermissions.AUDIT_EXPORT + "')")
    /**
     * Note: This endpoint intentionally returns 202 Accepted (not 201 Created)
     * because
     * audit export job submission is an async operation. The job is created and
     * queued
     * immediately, but execution is deferred. 202 Accepted is semantically correct
     * per
     * RFC 7231 section 6.3.3 for asynchronous processing. ADR-0017 covers
     * synchronous resource
     * creation (201); this deviation is intentional for async job endpoints.
     */
    @Operation(operationId = "requestAuditExport", summary = "Request an Asynchronous Audit Export", description = """
                    Submits an asynchronous audit export job and answers 202 Accepted with the job id and an \
                    initial PENDING status.
                    Use this tool for bulk extraction of audit data as a file; use searchAuditEvents instead for \
                    interactive paged queries.
                    Preconditions: the caller must hold security:audit:export; jobs are currently held in an \
                    in-memory store, so they do not survive a service restart.
                    Required inputs: format (CSV or JSON) and deliveryMode (DOWNLOAD or WEBHOOK); filters is \
                    optional and scopes the export with the same criteria as searchAuditEvents.
                    Emits a SECURITY_AUDIT_EXPORT_REQUEST event; execution is deferred, so callers must poll \
                    getAuditExportJob for status and the eventual download URL.
                    Returns 400 when format or deliveryMode is missing or not a valid enum value.
                    """)
    @ApiResponse(
            responseCode = "202",
            description = "Export job created",
            content = @Content(schema = @Schema(implementation = AuditExportJobResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description = "Invalid export request",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Insufficient authority",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<AuditExportJobResponse> requestAuditExport(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Format, delivery mode, and optional filter scope of the export job.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "CSV download export", value = """
                                                                    {"format":"CSV",
                                                                     "deliveryMode":"DOWNLOAD",
                                                                     "filters":{"eventType":"PERMISSION_DENIED"}}
                                                                    """)))
                    @RequestBody
                    @Valid
                    @NonNull
                    AuditExportRequest request) {
        AuditExportJobResponse response = auditExportService.requestExport(request);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    @GetMapping("/{jobId}")
    @PreAuthorize("hasAuthority('" + SecurityPermissions.AUDIT_EXPORT + "')")
    @Operation(operationId = "getAuditExportJob", summary = "Get Audit Export Job Status", description = """
                    Returns the current status of a previously submitted audit export job, including completion \
                    time, download URL, and error message when present.
                    Use this tool to poll a job created by requestAuditExport; do not resubmit the export while a \
                    job is still PENDING.
                    Preconditions: the caller must hold security:audit:export and the job must exist in the \
                    in-memory store, which is cleared on service restart.
                    Required inputs: jobId (UUID) as a path parameter.
                    No events are emitted and no state changes; this is a read-only status projection.
                    Returns 404 when the job id is unknown or the store was cleared by a restart.
                    """)
    @ApiResponse(
            responseCode = "200",
            description = "Export job status",
            content = @Content(schema = @Schema(implementation = AuditExportJobResponse.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Insufficient authority",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Export job not found",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<AuditExportJobResponse> getAuditExportJob(
            @Parameter(
                            description = "Export job UUID",
                            required = true,
                            example = "550e8400-e29b-41d4-a716-446655440000")
                    @PathVariable
                    @NonNull
                    UUID jobId) {
        return ResponseEntity.ok(auditExportService.getExportJob(jobId));
    }
}
