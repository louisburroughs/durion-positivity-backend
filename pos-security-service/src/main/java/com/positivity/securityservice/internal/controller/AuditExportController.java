package com.positivity.securityservice.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.securityservice.internal.dto.AuditExportJobResponse;
import com.positivity.securityservice.internal.dto.AuditExportRequest;
import com.positivity.securityservice.service.AuditExportService;
import com.positivity.shared.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
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
    @PreAuthorize("hasAuthority('security:audit:export')")
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
    @Operation(
            operationId = "requestAuditExport",
            summary = "Request audit export",
            description =
                    "Submits an asynchronous audit export job. Returns 202 Accepted with the created job reference.")
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
            @RequestBody @Valid @NonNull AuditExportRequest request) {
        AuditExportJobResponse response = auditExportService.requestExport(request);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    @GetMapping("/{jobId}")
    @PreAuthorize("hasAuthority('security:audit:export')")
    @Operation(
            operationId = "getAuditExportJob",
            summary = "Get audit export job status",
            description = "Returns the current status of a previously submitted audit export job.")
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
