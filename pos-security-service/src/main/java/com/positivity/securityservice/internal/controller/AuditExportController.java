package com.positivity.securityservice.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.securityservice.internal.dto.AuditExportDownload;
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
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
                    Submits an asynchronous audit export job for the caller's tenant and answers 202 Accepted \
                    with the job id and an initial PENDING status.
                    Use this tool for bulk extraction of audit data as a file; use searchAuditEvents instead for \
                    interactive paged queries.
                    Preconditions: the caller must hold security:audit:export; jobs are persisted per tenant, run \
                    in the background once the request commits, and are deleted with their file after the \
                    configured retention period.
                    Required inputs: format (CSV or JSON) and deliveryMode, which must be DOWNLOAD; filters is \
                    optional and scopes the export with the same criteria as searchAuditEvents.
                    Emits a SECURITY_AUDIT_EXPORT_REQUEST event; poll getAuditExportJob until the job is \
                    COMPLETED, then fetch its downloadUrl with downloadAuditExport, or FAILED, where errorMessage \
                    says why (for example more matching events than the configured row limit).
                    Returns 400 when format or deliveryMode is missing or invalid or fromDate is not before \
                    toDate, and 400 AUDIT_EXPORT_WEBHOOK_UNSUPPORTED for WEBHOOK delivery, which has no \
                    configured destination yet.
                    """)
    @ApiResponse(
            responseCode = "202",
            description = "Export job created",
            content = @Content(schema = @Schema(implementation = AuditExportJobResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description = "Invalid export request, or WEBHOOK delivery (AUDIT_EXPORT_WEBHOOK_UNSUPPORTED)",
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
                    Returns the current status of an audit export job of the caller's tenant (PENDING, \
                    IN_PROGRESS, COMPLETED or FAILED) with its completion time, row count, download URL and \
                    error message when present.
                    Use this tool to poll a job created by requestAuditExport; do not resubmit the export while \
                    the job is still PENDING or IN_PROGRESS.
                    Preconditions: the caller must hold security:audit:export, and the job must belong to the \
                    caller's tenant, since another tenant's job id answers 404.
                    Required inputs: jobId (UUID) as a path parameter.
                    This is a read-only status projection that emits no events; a job interrupted by a service \
                    restart is moved to FAILED by a scheduled sweep once the configured timeout passes, so a poll \
                    never waits forever.
                    Returns 404 when the job id is unknown to the caller's tenant or the job was purged after the \
                    configured retention period (seven days by default).
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
                            example = "01960000-0000-7000-8000-000000000001")
                    @PathVariable
                    @NonNull
                    UUID jobId) {
        return ResponseEntity.ok(auditExportService.getExportJob(jobId));
    }

    @GetMapping("/{jobId}/download")
    @PreAuthorize("hasAuthority('" + SecurityPermissions.AUDIT_EXPORT + "')")
    @EmitEvent(id = "SECURITY_AUDIT_EXPORT_DOWNLOAD", apiVersion = "1")
    @Operation(operationId = "downloadAuditExport", summary = "Download a Completed Audit Export", description = """
                    Downloads the file a COMPLETED audit export job produced, as an attachment in the job's \
                    format (text/csv or application/json).
                    Use this tool after getAuditExportJob reports COMPLETED; the job's downloadUrl is this \
                    endpoint's gateway path.
                    Preconditions: the caller must hold security:audit:export and the job must belong to the \
                    caller's tenant.
                    Required inputs: jobId (UUID) as a path parameter.
                    Emits a SECURITY_AUDIT_EXPORT_DOWNLOAD event and changes no state; CSV cells that begin with \
                    a formula character are prefixed with a single quote so spreadsheets do not evaluate them.
                    Returns 409 AUDIT_EXPORT_NOT_READY while the job is PENDING, IN_PROGRESS or FAILED, and 404 \
                    when the job id is unknown to the caller's tenant or the job was purged after the retention \
                    period.
                    """)
    @ApiResponse(
            responseCode = "200",
            description = "Export file as an attachment",
            content = {
                @Content(mediaType = "text/csv", schema = @Schema(type = "string", format = "binary")),
                @Content(mediaType = "application/json", schema = @Schema(type = "string", format = "binary"))
            })
    @ApiResponse(
            responseCode = "403",
            description = "Insufficient authority",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Export job not found",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "Export job is not COMPLETED",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<byte[]> downloadAuditExport(
            @Parameter(
                            description = "Export job UUID",
                            required = true,
                            example = "01960000-0000-7000-8000-000000000001")
                    @PathVariable
                    @NonNull
                    UUID jobId) {
        AuditExportDownload file = auditExportService.getExportFile(jobId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.contentType()))
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(file.fileName())
                                .build()
                                .toString())
                .contentLength(file.content().length)
                .body(file.content());
    }
}
