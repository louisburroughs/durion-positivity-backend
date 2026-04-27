package com.positivity.accounting.internal.controller;

import com.positivity.accounting.internal.dto.ExportJobRequest;
import com.positivity.accounting.internal.dto.ExportJobResponse;
import com.positivity.accounting.service.TimekeepingExportService;
import com.positivity.events.EmitEvent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
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
  @SecurityRequirement(name = "bearerAuth", scopes = { "accounting:export:request" })
  @PreAuthorize("hasAuthority('accounting:export:request')")
  @EmitEvent(id = "ACCOUNTING_EXPORT_REQUEST", apiVersion = "1")
  @Operation(summary = "Request timekeeping export", operationId = "requestExport", description = "Submit an export job request.", tags = {
      "Accounting Exports" })
  @ApiResponse(responseCode = "202", description = "Export job accepted")
  @ApiResponse(responseCode = "400", description = "Invalid request")
  @ApiResponse(responseCode = "403", description = "Forbidden")
  public ResponseEntity<ExportJobResponse> requestExport(@Valid @RequestBody ExportJobRequest request) {
    ExportJobResponse response = timekeepingExportService.requestExport(request);
    return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
  }

  @GetMapping(value = "/status/{jobId}", produces = MediaType.APPLICATION_JSON_VALUE)
  @SecurityRequirement(name = "bearerAuth", scopes = { "accounting:export:view" })
  @PreAuthorize("hasAuthority('accounting:export:view')")
  @Operation(summary = "Get export job status", operationId = "getExportStatus", description = "Retrieve the current status of an export job.", tags = {
      "Accounting Exports" })
  @ApiResponse(responseCode = "200", description = "Export job status returned")
  @ApiResponse(responseCode = "404", description = "Export job not found")
  @ApiResponse(responseCode = "403", description = "Forbidden")
  public ResponseEntity<ExportJobResponse> getExportStatus(
      @Parameter(description = "Export job identifier") @PathVariable UUID jobId) {
    ExportJobResponse response = timekeepingExportService.getExportStatus(jobId);
    return ResponseEntity.ok(response);
  }

  @GetMapping(value = "/history", produces = MediaType.APPLICATION_JSON_VALUE)
  @SecurityRequirement(name = "bearerAuth", scopes = { "accounting:export:view" })
  @PreAuthorize("hasAuthority('accounting:export:view')")
  @Operation(summary = "List export history", operationId = "listExportHistory", description = "Retrieve paginated export job history.", tags = {
      "Accounting Exports" })
  @ApiResponse(responseCode = "200", description = "Export history returned")
  @ApiResponse(responseCode = "403", description = "Forbidden")
  public ResponseEntity<Page<ExportJobResponse>> listExportHistory(
      @PageableDefault(size = 20, sort = "requestedAt", direction = Sort.Direction.DESC) Pageable pageable) {
    Page<ExportJobResponse> history = timekeepingExportService.listExportHistory(pageable);
    return ResponseEntity.ok(history);
  }
}