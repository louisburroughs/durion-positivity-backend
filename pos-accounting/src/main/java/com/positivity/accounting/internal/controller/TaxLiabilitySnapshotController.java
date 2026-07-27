package com.positivity.accounting.internal.controller;

import com.positivity.accounting.internal.dto.TaxLiabilitySnapshotResponse;
import com.positivity.accounting.internal.dto.TaxLiabilitySnapshotSummary;
import com.positivity.accounting.internal.dto.TaxLiabilitySnapshotVerification;
import com.positivity.accounting.service.TaxLiabilitySnapshotService;
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
@Tag(name = "Financial Reporting for Tax Liability", description = "Period-close-aligned freeze of the Sales-Tax Liability report")
@Validated
public class TaxLiabilitySnapshotController {

        private static final String PERIOD_CODE_PATTERN = "^\\d{4}-(0[1-9]|1[0-2])$";

        private final TaxLiabilitySnapshotService snapshotService;

        public TaxLiabilitySnapshotController(@NonNull TaxLiabilitySnapshotService snapshotService) {
                this.snapshotService = snapshotService;
        }

        @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
        @SecurityRequirement(name = "bearerAuth", scopes = { "accounting:tax-snapshot:freeze" })
        @PreAuthorize("hasAuthority('accounting:tax-snapshot:freeze')")
        @EmitEvent(id = "TAX_LIABILITY_SNAPSHOT_FREEZE", apiVersion = "1")
        @Operation(summary = "Freeze the Sales-Tax Liability report for a closed period", description = "Generate the Sales-Tax Liability report over the accounting period's exact date range and persist"
                        + " it as an immutable snapshot with a canonical SHA-256 content hash. The period must be"
                        + " CLOSED. If an ACTIVE snapshot already exists for the period the request fails with 409"
                        + " unless supersede=true, which demotes the prior snapshot to SUPERSEDED (reopen → adjust"
                        + " → re-close flow) and preserves history.", tags = {
                                        "Financial Reporting", "Financial Reporting for Tax Liability" })
        @ApiResponse(responseCode = "201", description = "Snapshot frozen", content = @Content(schema = @Schema(implementation = TaxLiabilitySnapshotResponse.class)))
        @ApiResponse(responseCode = "400", description = "Invalid period code", content = @Content(schema = @Schema(implementation = ApiError.class)))
        @ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content(schema = @Schema(implementation = ApiError.class)))
        @ApiResponse(responseCode = "403", description = "Forbidden - missing accounting:tax-snapshot:freeze", content = @Content(schema = @Schema(implementation = ApiError.class)))
        @ApiResponse(responseCode = "404", description = "Accounting period not found", content = @Content(schema = @Schema(implementation = ApiError.class)))
        @ApiResponse(responseCode = "409", description = "Period not CLOSED, or an ACTIVE snapshot exists and supersede was not set", content = @Content(schema = @Schema(implementation = ApiError.class)))
        public ResponseEntity<TaxLiabilitySnapshotResponse> freezeSnapshot(
                        @Parameter(description = "Accounting period code (YYYY-MM)", required = true, example = "2026-06") @RequestParam @Pattern(regexp = PERIOD_CODE_PATTERN, message = "periodCode must be in YYYY-MM format") @NonNull String periodCode,
                        @Parameter(description = "Supersede an existing ACTIVE snapshot for the period") @RequestParam(defaultValue = "false") boolean supersede) {
                return ResponseEntity.status(HttpStatus.CREATED).body(snapshotService.freeze(periodCode, supersede));
        }

        @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
        @SecurityRequirement(name = "bearerAuth", scopes = { "reporting:view:financial-statements" })
        @PreAuthorize("hasAuthority('reporting:view:financial-statements')")
        @EmitEvent(id = "TAX_LIABILITY_SNAPSHOT_LIST", apiVersion = "1")
        @Operation(summary = "List Sales-Tax Liability snapshots", description = "List snapshot summaries (no rows), newest freeze first, optionally filtered by period code.", tags = {
                        "Financial Reporting", "Financial Reporting for Tax Liability" })
        @ApiResponse(responseCode = "200", description = "Snapshot summaries (empty list when none exist)", content = @Content(array = @ArraySchema(schema = @Schema(implementation = TaxLiabilitySnapshotSummary.class))))
        @ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content(schema = @Schema(implementation = ApiError.class)))
        @ApiResponse(responseCode = "403", description = "Forbidden - missing reporting:view:financial-statements", content = @Content(schema = @Schema(implementation = ApiError.class)))
        public ResponseEntity<List<TaxLiabilitySnapshotSummary>> listSnapshots(
                        @Parameter(description = "Optional accounting period code filter (YYYY-MM)", example = "2026-06") @RequestParam(required = false) @Pattern(regexp = PERIOD_CODE_PATTERN, message = "periodCode must be in YYYY-MM format") @Nullable String periodCode) {
                return ResponseEntity.ok(snapshotService.list(periodCode));
        }

        @GetMapping(value = "/{snapshotId}", produces = MediaType.APPLICATION_JSON_VALUE)
        @SecurityRequirement(name = "bearerAuth", scopes = { "reporting:view:financial-statements" })
        @PreAuthorize("hasAuthority('reporting:view:financial-statements')")
        @EmitEvent(id = "TAX_LIABILITY_SNAPSHOT_GET", apiVersion = "1")
        @Operation(summary = "Get a Sales-Tax Liability snapshot", description = "Full frozen snapshot including per-jurisdiction rows, totals, reconciliation, and hash.", tags = {
                        "Financial Reporting", "Financial Reporting for Tax Liability" })
        @ApiResponse(responseCode = "200", description = "Snapshot found", content = @Content(schema = @Schema(implementation = TaxLiabilitySnapshotResponse.class)))
        @ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content(schema = @Schema(implementation = ApiError.class)))
        @ApiResponse(responseCode = "403", description = "Forbidden - missing reporting:view:financial-statements", content = @Content(schema = @Schema(implementation = ApiError.class)))
        @ApiResponse(responseCode = "404", description = "Snapshot not found", content = @Content(schema = @Schema(implementation = ApiError.class)))
        public ResponseEntity<TaxLiabilitySnapshotResponse> getSnapshot(
                        @Parameter(description = "Snapshot id", required = true) @PathVariable @NonNull UUID snapshotId) {
                return ResponseEntity.ok(snapshotService.get(snapshotId));
        }

        @PostMapping(value = "/{snapshotId}/verify", produces = MediaType.APPLICATION_JSON_VALUE)
        @SecurityRequirement(name = "bearerAuth", scopes = { "reporting:view:financial-statements" })
        @PreAuthorize("hasAuthority('reporting:view:financial-statements')")
        @EmitEvent(id = "TAX_LIABILITY_SNAPSHOT_VERIFY", apiVersion = "1")
        @Operation(summary = "Verify a snapshot against live data", description = "Re-derive the Sales-Tax Liability report for the snapshot's period from the live replicas and"
                        + " compare canonical hashes. Read-only: the snapshot is never mutated. Inconsistency"
                        + " means underlying data moved after the freeze (e.g. postings into a reopened period);"
                        + " re-freeze with supersede=true once the period is closed again.", tags = {
                                        "Financial Reporting", "Financial Reporting for Tax Liability" })
        @ApiResponse(responseCode = "200", description = "Verification result (consistent=true when hashes match)", content = @Content(schema = @Schema(implementation = TaxLiabilitySnapshotVerification.class)))
        @ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content(schema = @Schema(implementation = ApiError.class)))
        @ApiResponse(responseCode = "403", description = "Forbidden - missing reporting:view:financial-statements", content = @Content(schema = @Schema(implementation = ApiError.class)))
        @ApiResponse(responseCode = "404", description = "Snapshot not found", content = @Content(schema = @Schema(implementation = ApiError.class)))
        public ResponseEntity<TaxLiabilitySnapshotVerification> verifySnapshot(
                        @Parameter(description = "Snapshot id", required = true) @PathVariable @NonNull UUID snapshotId) {
                return ResponseEntity.ok(snapshotService.verify(snapshotId));
        }
}
