package com.positivity.accounting.internal.controller;

import com.positivity.accounting.internal.dto.LaborOverheadCostReport;
import com.positivity.accounting.internal.exception.InvalidRequestParameterException;
import com.positivity.accounting.internal.security.AccountingPermissions;
import com.positivity.accounting.internal.service.LaborOverheadReportService;
import com.positivity.accounting.internal.service.LocationHierarchyService;
import com.positivity.events.EmitEvent;
import com.positivity.security.common.SecurityContextHelper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import java.util.UUID;
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
    private final LocationHierarchyService locationHierarchyService;

    public LaborOverheadReportController(
            LaborOverheadReportService laborOverheadReportService, LocationHierarchyService locationHierarchyService) {
        this.laborOverheadReportService = laborOverheadReportService;
        this.locationHierarchyService = locationHierarchyService;
    }

    /**
     * Generate the Labor &amp; Overhead Cost Report for a location and fiscal year.
     */
    @GetMapping(value = "/labor-overhead", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('" + AccountingPermissions.REPORTING_VIEW_FINANCIAL_STATEMENTS + "')")
    @EmitEvent(id = "REPORT_LABOR_OVERHEAD_GENERATE", apiVersion = "1")
    @Operation(
            operationId = "generateLaborOverheadReport",
            summary = "Generate Labor Overhead Cost Report",
            description = """
                    Generates the read-only Location Labor and Overhead Cost Report: the canonical cost-line \
                    matrix of 12 monthly amounts plus YTD with section subtotals, for one location and fiscal \
                    year, derived entirely from posted GL data.
                    Use this tool for location-level labor and overhead cost review; do not use \
                    generateIncomeStatement, which is the org-wide statement without the location dimension.
                    Preconditions: none; a location with no posted activity yields zeroed lines.
                    Required inputs: locationId (the accounting locationId dimension, e.g. LOC-107) and \
                    fiscalYear (four-digit year); asOfMonth (1-12) is optional and defaults to 12, bounding \
                    the YTD column.
                    Emits a REPORT_LABOR_OVERHEAD_GENERATE audit event; no state changes.
                    Returns 400 when fiscalYear is not a four-digit year or asOfMonth is outside 1 to 12, \
                    and 403 with LOCATION_SCOPE_DENIED when the caller holds \
                    reporting:view:financial-statements but the token scopes it to locations that do not \
                    cover locationId (ADR-0061); an accounting location code the location replica cannot \
                    place is denied for a location-scoped caller.
                    """,
            tags = {"Location Cost Reporting"})
    @ApiResponse(
            responseCode = "200",
            description = "Report generated successfully",
            content = @Content(schema = @Schema(implementation = LaborOverheadCostReport.class)))
    @ApiResponse(responseCode = "400", description = "Invalid locationId, fiscalYear, or asOfMonth")
    @ApiResponse(responseCode = "401", description = "Unauthorized")
    @ApiResponse(
            responseCode = "403",
            description = "FORBIDDEN when the caller lacks reporting:view:financial-statements;"
                    + " LOCATION_SCOPE_DENIED when the caller holds it but the token scopes it to locations"
                    + " that do not cover locationId (ADR-0061)")
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

        // InvalidRequestParameterException maps to 400 Bad Request via the module's
        // @RestControllerAdvice, matching the accounting domain's standard ApiError contract.
        if (fiscalYear < MIN_FISCAL_YEAR || fiscalYear > MAX_FISCAL_YEAR) {
            throw new InvalidRequestParameterException("fiscalYear must be a four-digit year");
        }
        if (asOfMonth != null && (asOfMonth < MIN_MONTH || asOfMonth > MAX_MONTH)) {
            throw new InvalidRequestParameterException("asOfMonth must be between 1 and 12");
        }

        // ADR-0061 §3 (#1885): @PreAuthorize answered "may this caller read financial statements";
        // this answers "...for this location". Accounting names a location by its GL dimension
        // code (LOC-107), not by the owner's UUID, so the code is resolved against this module's
        // own ext_location replica first. A code the replica cannot place is handed to the check
        // unresolved: LocationScope denies a scoped caller on an unparseable location (fail
        // closed) and leaves a global or pre-rollout caller exactly as before.
        String scopeSubject = locationHierarchyService
                .locationIdForCode(locationId)
                .map(UUID::toString)
                .orElse(locationId);
        SecurityContextHelper.locationScope()
                .require(AccountingPermissions.REPORTING_VIEW_FINANCIAL_STATEMENTS, scopeSubject);

        LaborOverheadCostReport report = laborOverheadReportService.generate(locationId, fiscalYear, asOfMonth);
        return ResponseEntity.ok(report);
    }
}
