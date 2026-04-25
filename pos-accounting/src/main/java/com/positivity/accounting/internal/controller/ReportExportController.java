package com.positivity.accounting.internal.controller;

import com.positivity.accounting.internal.dto.ReportExportRequest;
import com.positivity.accounting.internal.dto.ReportExportResponse;
import com.positivity.accounting.service.ReportExportService;
import com.positivity.events.EmitEvent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
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
@SecurityRequirement(name = "bearerAuth", scopes = { "accounting:report:export" })
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
  @PreAuthorize("hasAuthority('accounting:report:export')")
  @EmitEvent(id = "ACCOUNTING_REPORT_EXPORT_REQUEST", apiVersion = "1")
  @Operation(summary = "Request async report export", description = "Submit an asynchronous report export job. Returns immediately with PENDING status.", tags = {
      "Financial Reporting" })
  @ApiResponse(responseCode = "201", description = "Export job created", content = @Content(schema = @Schema(implementation = ReportExportResponse.class)))
  @ApiResponse(responseCode = "400", description = "Invalid request payload")
  @ApiResponse(responseCode = "401", description = "Unauthorized")
  @ApiResponse(responseCode = "403", description = "Forbidden - missing accounting:report:export")
  public ResponseEntity<ReportExportResponse> requestExport(
      @Valid @RequestBody ReportExportRequest request,
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
  @PreAuthorize("hasAuthority('accounting:report:export')")
  @EmitEvent(id = "ACCOUNTING_REPORT_EXPORT_STATUS", apiVersion = "1")
  @Operation(summary = "Get export status", description = "Poll the current status of an async report export job.", tags = {
      "Financial Reporting" })
  @ApiResponse(responseCode = "200", description = "Export status retrieved", content = @Content(schema = @Schema(implementation = ReportExportResponse.class)))
  @ApiResponse(responseCode = "401", description = "Unauthorized")
  @ApiResponse(responseCode = "403", description = "Forbidden - missing accounting:report:export")
  @ApiResponse(responseCode = "404", description = "Export job not found")
  public ResponseEntity<ReportExportResponse> getExportStatus(
      @Parameter(description = "Export job UUID", required = true) @PathVariable UUID exportId) {

    ReportExportResponse response = reportExportService.getExportStatus(exportId);
    return ResponseEntity.ok(response);
  }

  /**
   * List export history (paginated, most recent first).
   */
  @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
  @PreAuthorize("hasAuthority('accounting:report:export')")
  @EmitEvent(id = "ACCOUNTING_REPORT_EXPORT_LIST", apiVersion = "1")
  @Operation(summary = "List export history", description = "List all async report export jobs, paginated and sorted by most-recent first.", tags = {
      "Financial Reporting" })
  @ApiResponse(responseCode = "200", description = "Export history returned")
  @ApiResponse(responseCode = "401", description = "Unauthorized")
  @ApiResponse(responseCode = "403", description = "Forbidden - missing accounting:report:export")
  public ResponseEntity<Page<ReportExportResponse>> getExportHistory(
      @PageableDefault(size = 20, sort = "requestedAt", direction = Sort.Direction.DESC) Pageable pageable) {

    Page<ReportExportResponse> page = reportExportService.getExportHistory(pageable);
    return ResponseEntity.ok(page);
  }

  private void validateDateRange(ReportExportRequest request) {
    if (request.getStartDate() != null && request.getEndDate() != null
        && request.getEndDate().isBefore(request.getStartDate())) {
      throw new IllegalArgumentException("endDate must be on or after startDate");
    }
  }
}
