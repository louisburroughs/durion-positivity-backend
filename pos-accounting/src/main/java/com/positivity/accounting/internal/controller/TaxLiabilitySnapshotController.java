package com.positivity.accounting.internal.controller;

import com.positivity.accounting.internal.dto.TaxLiabilitySnapshotResponse;
import com.positivity.accounting.internal.dto.TaxLiabilitySnapshotSummary;
import com.positivity.accounting.internal.dto.TaxLiabilitySnapshotVerification;
import com.positivity.accounting.internal.security.AccountingPermissions;
import com.positivity.accounting.internal.service.TaxLiabilitySnapshotService;
import com.positivity.events.EmitEvent;
import com.positivity.shared.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST API for the period-close-aligned Sales-Tax Liability freeze (issue #998,
 * Phase-2 scope
 * item 2).
 *
 * <p>
 * Freezing captures the T8 report (issue #966) for a CLOSED accounting period
 * as an immutable,
 * re-derivable snapshot with a canonical SHA-256 content hash; verify
 * re-derives the report from
 * live data and compares. Provider-neutral per the #998 decision record —
 * snapshots carry no
 * filing-provider identifiers.
 */
@RestController
@RequestMapping("/v1/accounting/reports/financial/tax-liability/snapshots")
@Tag(
        name = "Financial Reporting for Tax Liability",
        description = "Period-close-aligned freeze of the Sales-Tax Liability report")
@Validated
public class TaxLiabilitySnapshotController {

    private static final String PERIOD_CODE_PATTERN = "^\\d{4}-(0[1-9]|1[0-2])$";

    private final TaxLiabilitySnapshotService snapshotService;

    public TaxLiabilitySnapshotController(@NonNull TaxLiabilitySnapshotService snapshotService) {
        this.snapshotService = snapshotService;
    }

    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:tax-snapshot:freeze"})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.TAX_SNAPSHOT_FREEZE + "')")
    @EmitEvent(id = "TAX_LIABILITY_SNAPSHOT_FREEZE", apiVersion = "1")
    @Operation(
            operationId = "freezeTaxLiabilitySnapshot",
            summary = "Freeze Sales-Tax Liability Snapshot",
            description = """
                    Generates the Sales-Tax Liability report over a closed accounting period's exact date \
                    range and persists it as an immutable snapshot with a canonical SHA-256 content hash; \
                    snapshots are provider-neutral and carry no filing-provider identifiers.
                    Use this tool at period close to fix filing figures; do not use \
                    generateTaxLiabilityReport, which computes the live report without freezing anything.
                    Preconditions: the accounting period must exist and be CLOSED, and no ACTIVE snapshot may \
                    exist for the period unless supersede is set.
                    Required inputs: periodCode (YYYY-MM) as a query parameter; supersede defaults to false \
                    and, when true, demotes the prior ACTIVE snapshot to SUPERSEDED while preserving history.
                    Emits a TAX_LIABILITY_SNAPSHOT_FREEZE event.
                    Returns 404 when the period does not exist, 409 when the period is not CLOSED or an \
                    ACTIVE snapshot exists without supersede, and 400 when the period code is malformed.
                    """,
            tags = {"Financial Reporting", "Financial Reporting for Tax Liability"})
    @ApiResponse(
            responseCode = "201",
            description = "Snapshot frozen",
            content = @Content(schema = @Schema(implementation = TaxLiabilitySnapshotResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description = "Invalid period code",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "401",
            description = "Unauthorized",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Forbidden - missing accounting:tax-snapshot:freeze",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Accounting period not found",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "Period not CLOSED, or an ACTIVE snapshot exists and supersede was not set",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<TaxLiabilitySnapshotResponse> freezeSnapshot(
            @Parameter(description = "Accounting period code (YYYY-MM)", required = true, example = "2026-06")
                    @RequestParam
                    @Pattern(regexp = PERIOD_CODE_PATTERN, message = "periodCode must be in YYYY-MM format")
                    @NonNull
                    String periodCode,
            @Parameter(description = "Supersede an existing ACTIVE snapshot for the period")
                    @RequestParam(defaultValue = "false")
                    boolean supersede) {
        return ResponseEntity.status(HttpStatus.CREATED).body(snapshotService.freeze(periodCode, supersede));
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"reporting:view:financial-statements"})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.REPORTING_VIEW_FINANCIAL_STATEMENTS + "')")
    @EmitEvent(id = "TAX_LIABILITY_SNAPSHOT_LIST", apiVersion = "1")
    @Operation(
            operationId = "listTaxLiabilitySnapshots",
            summary = "List Sales-Tax Liability Snapshots",
            description = """
                    Lists Sales-Tax Liability snapshot summaries without their row detail, newest freeze \
                    first, optionally filtered by period code.
                    Use this tool to find which periods are frozen and which snapshot is ACTIVE; use \
                    getTaxLiabilitySnapshot instead for a snapshot's full frozen content.
                    Preconditions: none.
                    Required inputs: none; periodCode (YYYY-MM) is an optional filter.
                    Emits a TAX_LIABILITY_SNAPSHOT_LIST audit event; no state changes.
                    Returns 200 with an empty list when no snapshots exist.
                    """,
            tags = {"Financial Reporting", "Financial Reporting for Tax Liability"})
    @ApiResponse(
            responseCode = "200",
            description = "Snapshot summaries (empty list when none exist)",
            content =
                    @Content(
                            array = @ArraySchema(schema = @Schema(implementation = TaxLiabilitySnapshotSummary.class))))
    @ApiResponse(
            responseCode = "401",
            description = "Unauthorized",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Forbidden - missing reporting:view:financial-statements",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<List<TaxLiabilitySnapshotSummary>> listSnapshots(
            @Parameter(description = "Optional accounting period code filter (YYYY-MM)", example = "2026-06")
                    @RequestParam(required = false)
                    @Pattern(regexp = PERIOD_CODE_PATTERN, message = "periodCode must be in YYYY-MM format")
                    @Nullable
                    String periodCode) {
        return ResponseEntity.ok(snapshotService.list(periodCode));
    }

    @GetMapping(value = "/{snapshotId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"reporting:view:financial-statements"})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.REPORTING_VIEW_FINANCIAL_STATEMENTS + "')")
    @EmitEvent(id = "TAX_LIABILITY_SNAPSHOT_GET", apiVersion = "1")
    @Operation(
            operationId = "getTaxLiabilitySnapshot",
            summary = "Get Sales-Tax Liability Snapshot",
            description = """
                    Returns one frozen Sales-Tax Liability snapshot in full: per-jurisdiction rows, totals, \
                    reconciliation and the canonical content hash.
                    Use this tool to read filing figures exactly as frozen; use listTaxLiabilitySnapshots \
                    instead when hunting for the right snapshot, and verifyTaxLiabilitySnapshot to check it \
                    against live data.
                    Preconditions: the snapshot must exist.
                    Required inputs: snapshotId (UUID) as a path parameter; there is no request body.
                    Emits a TAX_LIABILITY_SNAPSHOT_GET audit event; the snapshot is immutable and never \
                    changed by reads.
                    Returns 404 TAX_SNAPSHOT_NOT_FOUND when no snapshot exists for the supplied id.
                    """,
            tags = {"Financial Reporting", "Financial Reporting for Tax Liability"})
    @ApiResponse(
            responseCode = "200",
            description = "Snapshot found",
            content = @Content(schema = @Schema(implementation = TaxLiabilitySnapshotResponse.class)))
    @ApiResponse(
            responseCode = "401",
            description = "Unauthorized",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Forbidden - missing reporting:view:financial-statements",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Snapshot not found",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<TaxLiabilitySnapshotResponse> getSnapshot(
            @Parameter(description = "Snapshot id", required = true) @PathVariable @NonNull UUID snapshotId) {
        return ResponseEntity.ok(snapshotService.get(snapshotId));
    }

    @PostMapping(value = "/{snapshotId}/verify", produces = MediaType.APPLICATION_JSON_VALUE)
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"reporting:view:financial-statements"})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.REPORTING_VIEW_FINANCIAL_STATEMENTS + "')")
    @EmitEvent(id = "TAX_LIABILITY_SNAPSHOT_VERIFY", apiVersion = "1")
    @Operation(
            operationId = "verifyTaxLiabilitySnapshot",
            summary = "Verify Sales-Tax Liability Snapshot",
            description = """
                    Re-derives the Sales-Tax Liability report for a snapshot's period from the live replicas \
                    and compares canonical hashes, reporting consistent=true when they match.
                    Use this tool to detect drift after a freeze; do not use freezeTaxLiabilitySnapshot to \
                    check consistency, since freezing creates state.
                    Preconditions: the snapshot must exist; inconsistency means underlying data moved after \
                    the freeze, for example postings into a reopened period, and the remedy is re-freezing \
                    with supersede=true once the period is closed again.
                    Required inputs: snapshotId (UUID) as a path parameter; there is no request body.
                    Emits a TAX_LIABILITY_SNAPSHOT_VERIFY audit event; the snapshot itself is never mutated.
                    Returns 404 TAX_SNAPSHOT_NOT_FOUND when no snapshot exists for the supplied id.
                    """,
            tags = {"Financial Reporting", "Financial Reporting for Tax Liability"})
    @ApiResponse(
            responseCode = "200",
            description = "Verification result (consistent=true when hashes match)",
            content = @Content(schema = @Schema(implementation = TaxLiabilitySnapshotVerification.class)))
    @ApiResponse(
            responseCode = "401",
            description = "Unauthorized",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Forbidden - missing reporting:view:financial-statements",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Snapshot not found",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<TaxLiabilitySnapshotVerification> verifySnapshot(
            @Parameter(description = "Snapshot id", required = true) @PathVariable @NonNull UUID snapshotId) {
        return ResponseEntity.ok(snapshotService.verify(snapshotId));
    }
}
