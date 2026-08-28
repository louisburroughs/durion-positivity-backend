package com.positivity.accounting.internal.controller;

import com.positivity.accounting.internal.dto.ExportJobRequest;
import com.positivity.accounting.internal.dto.ExportJobResponse;
import com.positivity.accounting.internal.security.AccountingPermissions;
import com.positivity.accounting.internal.service.TimekeepingExportService;
import com.positivity.events.EmitEvent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
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
@RequestMapping("/v1/accounting/export")
@Tag(name = "Accounting Exports", description = "Manage timekeeping and generic data export jobs.")
public class TimekeepingExportController {

    private final TimekeepingExportService timekeepingExportService;

    public TimekeepingExportController(@NonNull TimekeepingExportService timekeepingExportService) {
        this.timekeepingExportService = timekeepingExportService;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:export:request"})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.EXPORT_REQUEST + "')")
    @EmitEvent(id = "ACCOUNTING_EXPORT_REQUEST", apiVersion = "1")
    @Operation(
            operationId = "requestExport",
            summary = "Request Timekeeping Export",
            description = """
                    Submits an asynchronous timekeeping or generic data export job and returns it in its \
                    initial accepted state.
                    Use this tool to export timekeeping data; do not use requestReportExport, which renders \
                    financial-statement reports, and poll getExportStatus for completion.
                    Preconditions: none; the job is queued for asynchronous processing.
                    Required inputs: exportType and format (both non-blank strings); filters (JSON object) \
                    and deliveryMode are optional.
                    Emits an ACCOUNTING_EXPORT_REQUEST event and returns 202 while the job runs \
                    asynchronously.
                    Returns 400 when exportType or format is missing or blank.
                    """,
            tags = {"Accounting Exports"})
    @ApiResponse(responseCode = "202", description = "Export job accepted")
    @ApiResponse(responseCode = "400", description = "Invalid request")
    @ApiResponse(responseCode = "403", description = "Forbidden")
    public ResponseEntity<ExportJobResponse> requestExport(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Export job definition with type, format and optional filters.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Timekeeping CSV export", value = """
                                                                    {"exportType":"TIMEKEEPING",
                                                                     "format":"CSV",
                                                                     "filters":{"from":"2026-08-01","to":"2026-08-13"}}
                                                                    """)))
                    @Valid
                    @RequestBody
                    ExportJobRequest request) {
        ExportJobResponse response = timekeepingExportService.requestExport(request);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    @GetMapping(value = "/status/{jobId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:export:view"})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.EXPORT_VIEW + "')")
    @Operation(
            operationId = "getExportStatus",
            summary = "Get Export Job Status",
            description = """
                    Returns the current status of one timekeeping or generic export job.
                    Use this tool to poll a job created by requestExport; use listExportHistory instead to \
                    browse all past jobs.
                    Preconditions: the export job must exist.
                    Required inputs: jobId (UUID) as a path parameter; there is no request body.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 404 EXPORT_JOB_NOT_FOUND when no export job exists for the supplied id.
                    """,
            tags = {"Accounting Exports"})
    @ApiResponse(responseCode = "200", description = "Export job status returned")
    @ApiResponse(responseCode = "404", description = "Export job not found")
    @ApiResponse(responseCode = "403", description = "Forbidden")
    public ResponseEntity<ExportJobResponse> getExportStatus(
            @Parameter(description = "Export job identifier") @PathVariable UUID jobId) {
        ExportJobResponse response = timekeepingExportService.getExportStatus(jobId);
        return ResponseEntity.ok(response);
    }

    @GetMapping(value = "/history", produces = MediaType.APPLICATION_JSON_VALUE)
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:export:view"})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.EXPORT_VIEW + "')")
    @Operation(
            operationId = "listExportHistory",
            summary = "List Export Job History",
            description = """
                    Lists timekeeping and generic export jobs as a paginated projection, most recent first.
                    Use this tool to review past export jobs; do not use getExportStatus, which polls one \
                    job by its id.
                    Preconditions: none beyond the caller holding accounting:export:view.
                    Required inputs: none; the page defaults to 20 items and only requestedAt is a supported \
                    sort property.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 400 UNSUPPORTED_SORT_PROPERTY when an unsupported sort property is requested.
                    """,
            tags = {"Accounting Exports"})
    @ApiResponse(responseCode = "200", description = "Export history returned")
    @ApiResponse(responseCode = "403", description = "Forbidden")
    public ResponseEntity<Page<ExportJobResponse>> listExportHistory(
            @ParameterObject @PageableDefault(size = 20, sort = "requestedAt", direction = Sort.Direction.DESC)
                    Pageable pageable) {
        Page<ExportJobResponse> history = timekeepingExportService.listExportHistory(pageable);
        return ResponseEntity.ok(history);
    }
}
