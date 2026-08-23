package com.positivity.accounting.internal.controller;

import com.positivity.accounting.internal.dto.ReportExportArtifact;
import com.positivity.accounting.internal.dto.ReportExportRequest;
import com.positivity.accounting.internal.dto.ReportExportResponse;
import com.positivity.accounting.internal.security.AccountingPermissions;
import com.positivity.accounting.service.ReportExportService;
import com.positivity.events.EmitEvent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ContentDisposition;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST API for asynchronous financial report export operations.
 *
 * <p>
 * Grouped under the "Financial Reporting" OpenAPI tag so the generated
 * SDK service class is named {@code FinancialReportingService} and clients
 * can discover export operations alongside income-statement and balance-sheet
 * generation.
 *
 * @author Louis Burroughs
 * @since 2025-01-01
 */
@RestController
@RequestMapping("/v1/accounting/reports/export")
@Tag(name = "Financial Reporting", description = "Income Statement and Balance Sheet generation with drilldown")
@SecurityRequirement(
        name = "bearerAuth",
        scopes = {"accounting:report:export"})
@Validated
@RequiredArgsConstructor
public class ReportExportController {

    private final ReportExportService reportExportService;

    /**
     * Submit a new asynchronous report export request.
     *
     * <p>
     * Returns 201 Created with the initial export record (status = PENDING).
     * Poll {@code GET /v1/accounting/reports/export/{exportId}} for completion.
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('" + AccountingPermissions.REPORT_EXPORT + "')")
    @EmitEvent(id = "ACCOUNTING_REPORT_EXPORT_REQUEST", apiVersion = "1")
    @Operation(
            operationId = "requestReportExport",
            summary = "Request Async Report Export",
            description = """
                    Submits an asynchronous export job that renders a financial report to a downloadable \
                    artifact, returning immediately with status PENDING.
                    Use this tool to produce a file for one of the reporting endpoints' outputs; do not use \
                    the synchronous generate operations such as generateIncomeStatement when a file artifact \
                    is what is needed, and poll getReportExportStatus for completion.
                    Preconditions: none; as-of report types use the request's endDate as the as-of date.
                    Required inputs: format (PDF, CSV, XLSX or JSON), reportType (TAX_LIABILITY, \
                    INCOME_STATEMENT, BALANCE_SHEET, TRIAL_BALANCE, GENERAL_LEDGER, AGED_RECEIVABLES or \
                    AGED_PAYABLES), startDate and endDate (endDate on or after startDate); accountId and \
                    filename are optional.
                    Emits an ACCOUNTING_REPORT_EXPORT_REQUEST event and records the requesting operator.
                    Returns 400 when the payload is invalid or the end date precedes the start date.
                    """,
            tags = {"Financial Reporting"})
    @ApiResponse(
            responseCode = "201",
            description = "Export job created",
            content = @Content(schema = @Schema(implementation = ReportExportResponse.class)))
    @ApiResponse(responseCode = "400", description = "Invalid request payload")
    @ApiResponse(responseCode = "401", description = "Unauthorized")
    @ApiResponse(responseCode = "403", description = "Forbidden - missing accounting:report:export")
    public ResponseEntity<ReportExportResponse> requestExport(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Report type, format and date range to render asynchronously.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Trial balance CSV", value = """
                                                                    {"format":"CSV",
                                                                     "reportType":"TRIAL_BALANCE",
                                                                     "startDate":"2026-07-01",
                                                                     "endDate":"2026-07-31"}
                                                                    """)))
                    @Valid
                    @RequestBody
                    ReportExportRequest request,
            @AuthenticationPrincipal UserDetails principal) {

        String operatorId = (principal != null) ? principal.getUsername() : "unknown";
        validateDateRange(request);
        ReportExportResponse response = reportExportService.requestExport(request, operatorId);
        return ResponseEntity.status(201).body(response);
    }

    /**
     * Get the current status of an export job.
     */
    @GetMapping(value = "/{exportId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('" + AccountingPermissions.REPORT_EXPORT + "')")
    @EmitEvent(id = "ACCOUNTING_REPORT_EXPORT_STATUS", apiVersion = "1")
    @Operation(
            operationId = "getReportExportStatus",
            summary = "Get Report Export Status",
            description = """
                    Returns the current status of an asynchronous report export job: PENDING, IN_PROGRESS, \
                    COMPLETED or FAILED.
                    Use this tool to poll a job created by requestReportExport; do not use \
                    downloadReportExport until the status is COMPLETED.
                    Preconditions: the export job must exist.
                    Required inputs: exportId (UUID) as a path parameter; there is no request body.
                    Emits an ACCOUNTING_REPORT_EXPORT_STATUS audit event; no state changes.
                    Returns 404 EXPORT_JOB_NOT_FOUND when no export job exists for the supplied id.
                    """,
            tags = {"Financial Reporting"})
    @ApiResponse(
            responseCode = "200",
            description = "Export status retrieved",
            content = @Content(schema = @Schema(implementation = ReportExportResponse.class)))
    @ApiResponse(responseCode = "401", description = "Unauthorized")
    @ApiResponse(responseCode = "403", description = "Forbidden - missing accounting:report:export")
    @ApiResponse(responseCode = "404", description = "Export job not found")
    public ResponseEntity<ReportExportResponse> getExportStatus(
            @Parameter(description = "Export job UUID", required = true) @PathVariable UUID exportId) {

        ReportExportResponse response = reportExportService.getExportStatus(exportId);
        return ResponseEntity.ok(response);
    }

    /**
     * Download the rendered artifact of a completed export.
     *
     * <p>
     * Guarded by {@code reporting:view:financial-statements} (in addition to the
     * export permission on the submit/status endpoints) because the artifact
     * carries the actual financial-statement figures.
     */
    @GetMapping(value = "/{exportId}/download")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"reporting:view:financial-statements"})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.REPORTING_VIEW_FINANCIAL_STATEMENTS + "')")
    @EmitEvent(id = "ACCOUNTING_REPORT_EXPORT_DOWNLOAD", apiVersion = "1")
    @Operation(
            operationId = "downloadReportExport",
            summary = "Download Report Export Artifact",
            description = """
                    Downloads the rendered artifact of a COMPLETED export job as an attachment whose content \
                    type matches the requested format, for example text/csv or application/pdf.
                    Use this tool after getReportExportStatus reports COMPLETED; do not use it earlier, and \
                    note it requires reporting:view:financial-statements because the artifact carries actual \
                    financial figures.
                    Preconditions: the export job must exist, be COMPLETED and still have its artifact.
                    Required inputs: exportId (UUID) as a path parameter; there is no request body.
                    Emits an ACCOUNTING_REPORT_EXPORT_DOWNLOAD audit event; no state changes.
                    Returns 404 when the job or its artifact is missing, and 409 when the job is not \
                    COMPLETED.
                    """,
            tags = {"Financial Reporting"})
    @ApiResponse(
            responseCode = "200",
            description = "Rendered artifact returned (content type matches the export format, e.g. text/csv or"
                    + " application/pdf)",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_OCTET_STREAM_VALUE,
                            schema = @Schema(type = "string", format = "binary")))
    @ApiResponse(responseCode = "401", description = "Unauthorized")
    @ApiResponse(responseCode = "403", description = "Forbidden - missing reporting:view:financial-statements")
    @ApiResponse(responseCode = "404", description = "Export job or artifact not found")
    @ApiResponse(responseCode = "409", description = "Export job is not COMPLETED")
    public ResponseEntity<byte[]> downloadExport(
            @Parameter(description = "Export job UUID", required = true) @PathVariable UUID exportId) {

        ReportExportArtifact artifact = reportExportService.downloadExport(exportId);
        // The filename is sanitized to a safe character set when the artifact is created;
        // ContentDisposition additionally quotes/escapes it, preventing header injection.
        return ResponseEntity.ok()
                .headers(headers -> headers.setContentDisposition(ContentDisposition.attachment()
                        .filename(artifact.filename())
                        .build()))
                .contentType(MediaType.parseMediaType(artifact.contentType()))
                .body(artifact.content());
    }

    /**
     * List export history (paginated, most recent first).
     */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('" + AccountingPermissions.REPORT_EXPORT + "')")
    @EmitEvent(id = "ACCOUNTING_REPORT_EXPORT_LIST", apiVersion = "1")
    @Operation(
            operationId = "listReportExports",
            summary = "List Report Export History",
            description = """
                    Lists all asynchronous report export jobs as a paginated projection, most recent first.
                    Use this tool to review past exports and their statuses; do not use \
                    getReportExportStatus, which polls a single job by id.
                    Preconditions: none beyond the caller holding accounting:report:export.
                    Required inputs: none; the page defaults to 20 items and only requestedAt is a supported \
                    sort property.
                    Emits an ACCOUNTING_REPORT_EXPORT_LIST audit event; no state changes.
                    Returns 400 when an unsupported sort property is requested.
                    """,
            tags = {"Financial Reporting"})
    @ApiResponse(responseCode = "200", description = "Export history returned")
    @ApiResponse(responseCode = "401", description = "Unauthorized")
    @ApiResponse(responseCode = "403", description = "Forbidden - missing accounting:report:export")
    public ResponseEntity<Page<ReportExportResponse>> getExportHistory(
            @ParameterObject @PageableDefault(size = 20, sort = "requestedAt", direction = Sort.Direction.DESC)
                    Pageable pageable) {

        Page<ReportExportResponse> page = reportExportService.getExportHistory(pageable);
        return ResponseEntity.ok(page);
    }

    private void validateDateRange(ReportExportRequest request) {
        if (request.getStartDate() != null
                && request.getEndDate() != null
                && request.getEndDate().isBefore(request.getStartDate())) {
            throw new IllegalArgumentException("endDate must be on or after startDate");
        }
    }
}
