package com.positivity.accounting.internal.controller;

import com.positivity.accounting.internal.dto.LaborOverheadCostReport;
import com.positivity.accounting.service.LaborOverheadReportService;
import com.positivity.events.EmitEvent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST API for the CAP-316 read-only Location Labor &amp; Overhead Cost Report.
 *
 * <p>Returns the canonical cost-line matrix (12 monthly amounts + YTD, with section subtotals) for a
 * location and fiscal year, derived entirely from posted GL data. Lives alongside the financial
 * reporting endpoints and inherits the financial-reporting permission family (CAP-054).
 */
@RestController
@RequestMapping("/v1/accounting/reports/location")
@Tag(name = "Location Cost Reporting", description = "Read-only location Labor & Overhead Cost Report (CAP-316)")
@SecurityRequirement(
        name = "bearerAuth",
        scopes = {"reporting:view:financial-statements"})
@Validated
public class LaborOverheadReportController {

    private static final int MIN_MONTH = 1;
    private static final int MAX_MONTH = 12;
    private static final int MIN_FISCAL_YEAR = 1900;
    private static final int MAX_FISCAL_YEAR = 9999;

    private final LaborOverheadReportService laborOverheadReportService;

    public LaborOverheadReportController(LaborOverheadReportService laborOverheadReportService) {
        this.laborOverheadReportService = laborOverheadReportService;
    }

    /**
     * Generate the Labor &amp; Overhead Cost Report for a location and fiscal year.
     */
    @GetMapping(value = "/labor-overhead", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('reporting:view:financial-statements')")
    @EmitEvent(id = "REPORT_LABOR_OVERHEAD_GENERATE", apiVersion = "1")
    @Operation(
            summary = "Generate Labor & Overhead Cost Report",
            description = "Return the canonical Labor & Overhead cost-line matrix (12 monthly amounts + YTD, with"
                    + " section subtotals) for a location and fiscal year, derived from posted GL data.",
            tags = {"Location Cost Reporting"})
    @ApiResponse(
            responseCode = "200",
            description = "Report generated successfully",
            content = @Content(schema = @Schema(implementation = LaborOverheadCostReport.class)))
    @ApiResponse(responseCode = "400", description = "Invalid locationId, fiscalYear, or asOfMonth")
    @ApiResponse(responseCode = "401", description = "Unauthorized")
    @ApiResponse(responseCode = "403", description = "Forbidden - missing reporting:view:financial-statements")
    public ResponseEntity<LaborOverheadCostReport> generateLaborOverheadReport(
            @Parameter(
                            description = "Location/dealer identifier (accounting locationId dimension)",
                            required = true,
                            example = "LOC-107")
                    @RequestParam
                    @NotBlank
                    @NonNull
                    String locationId,
            @Parameter(description = "Fiscal year", required = true, example = "2026") @RequestParam int fiscalYear,
            @Parameter(description = "Highest elapsed month bounding YTD (1-12); defaults to 12", example = "6")
                    @RequestParam(required = false)
                    @Nullable
                    Integer asOfMonth) {

        // Validation errors use IllegalArgumentException to leverage the module's @RestControllerAdvice,
        // which maps them to 400 Bad Request with the accounting domain's standard ApiError contract.
        if (fiscalYear < MIN_FISCAL_YEAR || fiscalYear > MAX_FISCAL_YEAR) {
            throw new IllegalArgumentException("fiscalYear must be a four-digit year");
        }
        if (asOfMonth != null && (asOfMonth < MIN_MONTH || asOfMonth > MAX_MONTH)) {
            throw new IllegalArgumentException("asOfMonth must be between 1 and 12");
        }

        LaborOverheadCostReport report = laborOverheadReportService.generate(locationId, fiscalYear, asOfMonth);
        return ResponseEntity.ok(report);
    }
}
