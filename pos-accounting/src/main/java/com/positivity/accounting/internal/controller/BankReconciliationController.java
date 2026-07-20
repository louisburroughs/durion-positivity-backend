package com.positivity.accounting.internal.controller;

import com.positivity.accounting.internal.dto.AdjustmentTypeResponse;
import com.positivity.accounting.internal.dto.BankReconciliationImportRequest;
import com.positivity.accounting.internal.dto.BankReconciliationListResponse;
import com.positivity.accounting.internal.dto.BankReconciliationResponse;
import com.positivity.accounting.internal.dto.ReconciliationAdjustmentRequest;
import com.positivity.accounting.internal.dto.ReconciliationAuditResponse;
import com.positivity.accounting.internal.dto.ReconciliationMatchRequest;
import com.positivity.accounting.internal.dto.ReconciliationReportResponse;
import com.positivity.accounting.internal.dto.ReconciliationUnmatchRequest;
import com.positivity.accounting.internal.enums.BankAdjustmentType;
import com.positivity.accounting.internal.enums.ReconciliationStatus;
import com.positivity.accounting.service.BankReconciliationService;
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
import jakarta.validation.Valid;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for the manual CSV bank reconciliation workflow (Story F2, issue
 * #965, decisions D-5/D-6): import a bank statement CSV for a reconcilable GL cash
 * account, match statement lines to posted GL journal-entry lines, record
 * adjustments (which post real balanced journal entries through the accounting-period
 * gate), and finalize only when the statement and GL ending balances agree.
 *
 * <p>Reads require {@code accounting:reconciliation:view}; mutations require
 * {@code accounting:reconciliation:adjust}.
 */
@RestController
@RequestMapping("/v1/accounting/reconciliations")
@Tag(
        name = "Bank Reconciliation",
        description = "Manual CSV bank reconciliation: import a statement, match lines to posted GL entries,"
                + " record adjustments, and finalize when balanced.")
@RequiredArgsConstructor
@Validated
public class BankReconciliationController {

    private static final Logger log = LoggerFactory.getLogger(BankReconciliationController.class);

    private final BankReconciliationService bankReconciliationService;

    @PostMapping("/import")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:reconciliation:adjust"})
    @PreAuthorize("hasAuthority('accounting:reconciliation:adjust')")
    @EmitEvent(id = "ACCOUNTING_RECONCILIATION_IMPORT", apiVersion = "1")
    @Operation(
            summary = "Import a bank statement CSV and start a reconciliation",
            operationId = "importReconciliation",
            description = "Parses a bank statement CSV (columns: date, description, amount [signed], reference) for a"
                    + " reconcilable GL cash account and creates an IN_PROGRESS reconciliation with its imported"
                    + " statement lines (UNMATCHED). The GL ending balance is snapshotted from posted journal-entry"
                    + " lines on the account as-of the statement date. Returns 422 ACCOUNT_NOT_RECONCILABLE if the"
                    + " account's reconcilable flag (story H1) is false, and 400 if the CSV is malformed.",
            tags = {"Bank Reconciliation"})
    @ApiResponse(
            responseCode = "200",
            description = "Reconciliation created",
            content = @Content(schema = @Schema(implementation = BankReconciliationResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description = "Request body invalid or CSV malformed (ARGUMENT_NOT_VALID / VALIDATION_ERROR)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Caller lacks the accounting:reconciliation:adjust permission",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "422",
            description = "GL account is not reconcilable (ACCOUNT_NOT_RECONCILABLE)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<BankReconciliationResponse> importReconciliation(
            @Valid @RequestBody BankReconciliationImportRequest request) {
        log.info("Import bank reconciliation for account {}", request.getGlAccountId());
        return ResponseEntity.ok(bankReconciliationService.importStatement(request));
    }

    @GetMapping("/adjustment-types")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:reconciliation:view"})
    @PreAuthorize("hasAuthority('accounting:reconciliation:view')")
    @EmitEvent(id = "ACCOUNTING_RECONCILIATION_ADJUSTMENT_TYPES_LIST", apiVersion = "1")
    @Operation(
            summary = "List reconciliation adjustment types",
            operationId = "listReconciliationAdjustmentTypes",
            description = "Returns the supported reconciliation adjustment types (decision D-6) so the frontend never"
                    + " hardcodes the enum. No preconditions and no side effects.",
            tags = {"Bank Reconciliation"})
    @ApiResponse(
            responseCode = "200",
            description = "Adjustment types listed",
            content = @Content(array = @ArraySchema(schema = @Schema(implementation = AdjustmentTypeResponse.class))))
    @ApiResponse(
            responseCode = "403",
            description = "Caller lacks the accounting:reconciliation:view permission",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<List<AdjustmentTypeResponse>> listAdjustmentTypes() {
        List<AdjustmentTypeResponse> types = Arrays.stream(BankAdjustmentType.values())
                .map(AdjustmentTypeResponse::from)
                .toList();
        return ResponseEntity.ok(types);
    }

    @GetMapping
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:reconciliation:view"})
    @PreAuthorize("hasAuthority('accounting:reconciliation:view')")
    @EmitEvent(id = "ACCOUNTING_RECONCILIATION_LIST", apiVersion = "1")
    @Operation(
            summary = "List reconciliations",
            operationId = "listReconciliations",
            description = "Lists reconciliations, optionally filtered by glAccountId and/or status, most recent first."
                    + " Paginated. No side effects.",
            tags = {"Bank Reconciliation"})
    @ApiResponse(
            responseCode = "200",
            description = "Reconciliations listed",
            content = @Content(schema = @Schema(implementation = BankReconciliationListResponse.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Caller lacks the accounting:reconciliation:view permission",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<BankReconciliationListResponse> listReconciliations(
            @Parameter(description = "Filter by reconciled GL account id") @RequestParam(required = false)
                    UUID glAccountId,
            @Parameter(description = "Filter by reconciliation status") @RequestParam(required = false)
                    ReconciliationStatus status,
            @Parameter(description = "Zero-based page index", example = "0") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size", example = "20") @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        return ResponseEntity.ok(bankReconciliationService.list(glAccountId, status, pageable));
    }

    @GetMapping("/{reconciliationId}")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:reconciliation:view"})
    @PreAuthorize("hasAuthority('accounting:reconciliation:view')")
    @EmitEvent(id = "ACCOUNTING_RECONCILIATION_GET", apiVersion = "1")
    @Operation(
            summary = "Get a reconciliation",
            operationId = "getReconciliation",
            description = "Returns one reconciliation with its imported statement lines and adjustments. Returns 404"
                    + " RECONCILIATION_NOT_FOUND if the id is unknown.",
            tags = {"Bank Reconciliation"})
    @ApiResponse(
            responseCode = "200",
            description = "Reconciliation found",
            content = @Content(schema = @Schema(implementation = BankReconciliationResponse.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Caller lacks the accounting:reconciliation:view permission",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Reconciliation not found (RECONCILIATION_NOT_FOUND)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<BankReconciliationResponse> getReconciliation(
            @Parameter(description = "Reconciliation id", required = true) @PathVariable UUID reconciliationId) {
        return ResponseEntity.ok(bankReconciliationService.get(reconciliationId));
    }

    @PostMapping("/{reconciliationId}/match")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:reconciliation:adjust"})
    @PreAuthorize("hasAuthority('accounting:reconciliation:adjust')")
    @EmitEvent(id = "ACCOUNTING_RECONCILIATION_MATCH", apiVersion = "1")
    @Operation(
            summary = "Match statement lines to GL lines",
            operationId = "matchReconciliation",
            description = "Matches a set of statement lines to a set of posted GL journal-entry lines on the reconciled"
                    + " account (1-to-1 or N-to-1). The two sets must net to equal signed amounts within ±0.01."
                    + " Marks the statement lines MATCHED and records the linkage. Returns 404"
                    + " RECONCILIATION_NOT_FOUND if the reconciliation or a line is missing, 409"
                    + " RECONCILIATION_ALREADY_FINALIZED if the reconciliation is finalized, and 422"
                    + " MATCH_AMOUNT_MISMATCH if the sets do not net.",
            tags = {"Bank Reconciliation"})
    @ApiResponse(
            responseCode = "200",
            description = "Lines matched",
            content = @Content(schema = @Schema(implementation = BankReconciliationResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description = "Request body invalid (ARGUMENT_NOT_VALID / VALIDATION_ERROR)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Caller lacks the accounting:reconciliation:adjust permission",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Reconciliation or a referenced line not found (RECONCILIATION_NOT_FOUND)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "Reconciliation already finalized (RECONCILIATION_ALREADY_FINALIZED), or a statement/GL"
                    + " line is ineligible — not UNMATCHED, not POSTED, or already reconciled"
                    + " (RECONCILIATION_LINE_INELIGIBLE)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "422",
            description = "Matched sets do not net to equal amounts (MATCH_AMOUNT_MISMATCH)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<BankReconciliationResponse> matchReconciliation(
            @Parameter(description = "Reconciliation id", required = true) @PathVariable UUID reconciliationId,
            @Valid @RequestBody ReconciliationMatchRequest request) {
        log.info("Match lines in reconciliation {}", reconciliationId);
        return ResponseEntity.ok(bankReconciliationService.match(reconciliationId, request));
    }

    @PostMapping("/{reconciliationId}/unmatch")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:reconciliation:adjust"})
    @PreAuthorize("hasAuthority('accounting:reconciliation:adjust')")
    @EmitEvent(id = "ACCOUNTING_RECONCILIATION_UNMATCH", apiVersion = "1")
    @Operation(
            summary = "Reverse a match",
            operationId = "unmatchReconciliation",
            description = "Reverses a match by matchId or by statement line ids, returning the affected statement lines"
                    + " to UNMATCHED and releasing the linked GL lines. Returns 404 RECONCILIATION_NOT_FOUND if the"
                    + " reconciliation or match group is missing, and 409 RECONCILIATION_ALREADY_FINALIZED if the"
                    + " reconciliation is finalized.",
            tags = {"Bank Reconciliation"})
    @ApiResponse(
            responseCode = "200",
            description = "Match reversed",
            content = @Content(schema = @Schema(implementation = BankReconciliationResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description = "Neither matchId nor statementLineIds resolved to a single match group (VALIDATION_ERROR)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Caller lacks the accounting:reconciliation:adjust permission",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Reconciliation or match group not found (RECONCILIATION_NOT_FOUND)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "Reconciliation already finalized (RECONCILIATION_ALREADY_FINALIZED)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<BankReconciliationResponse> unmatchReconciliation(
            @Parameter(description = "Reconciliation id", required = true) @PathVariable UUID reconciliationId,
            @Valid @RequestBody ReconciliationUnmatchRequest request) {
        log.info("Unmatch in reconciliation {}", reconciliationId);
        return ResponseEntity.ok(bankReconciliationService.unmatch(reconciliationId, request));
    }

    @PostMapping("/{reconciliationId}/adjustments")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:reconciliation:adjust"})
    @PreAuthorize("hasAuthority('accounting:reconciliation:adjust')")
    @EmitEvent(id = "ACCOUNTING_RECONCILIATION_ADJUSTMENT", apiVersion = "1")
    @Operation(
            summary = "Record a reconciliation adjustment",
            operationId = "addReconciliationAdjustment",
            description = "Records a signed adjustment and posts a real balanced journal entry (Dr/Cr the reconciled"
                    + " cash account against the type's mapped counter account) through the accounting-period gate."
                    + " Returns 404 RECONCILIATION_NOT_FOUND if the reconciliation is missing, 409"
                    + " RECONCILIATION_ALREADY_FINALIZED if it is finalized, and 422 PERIOD_CLOSED / PERIOD_HARD_LOCKED"
                    + " if the adjustment JE is dated into a locked accounting period.",
            tags = {"Bank Reconciliation"})
    @ApiResponse(
            responseCode = "200",
            description = "Adjustment recorded and JE posted",
            content = @Content(schema = @Schema(implementation = BankReconciliationResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description = "Request body invalid or amount is zero (ARGUMENT_NOT_VALID / VALIDATION_ERROR)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Caller lacks the accounting:reconciliation:adjust permission",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Reconciliation not found (RECONCILIATION_NOT_FOUND)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "Reconciliation already finalized (RECONCILIATION_ALREADY_FINALIZED)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "422",
            description =
                    "Adjustment JE dated into a CLOSED or hard-locked period (PERIOD_CLOSED / PERIOD_HARD_LOCKED),"
                            + " or the amount sign is not permitted for the type — BANK_FEE/NSF_FEE must be negative,"
                            + " INTEREST_EARNED must be positive (RECONCILIATION_ADJUSTMENT_SIGN_INVALID)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<BankReconciliationResponse> addReconciliationAdjustment(
            @Parameter(description = "Reconciliation id", required = true) @PathVariable UUID reconciliationId,
            @Valid @RequestBody ReconciliationAdjustmentRequest request) {
        log.info("Record {} adjustment on reconciliation {}", request.getType(), reconciliationId);
        return ResponseEntity.ok(bankReconciliationService.addAdjustment(reconciliationId, request));
    }

    @PostMapping("/{reconciliationId}/finalize")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:reconciliation:adjust"})
    @PreAuthorize("hasAuthority('accounting:reconciliation:adjust')")
    @EmitEvent(id = "ACCOUNTING_RECONCILIATION_FINALIZE", apiVersion = "1")
    @Operation(
            summary = "Finalize a reconciliation",
            operationId = "finalizeReconciliation",
            description = "Finalizes a reconciliation (IN_PROGRESS to FINALIZED) only when it balances: the statement"
                    + " ending balance must equal the GL ending balance plus the sum of adjustments, within ±0.01"
                    + " (matched GL lines are already reflected in the GL ending balance). Returns 404"
                    + " RECONCILIATION_NOT_FOUND if missing, 409"
                    + " RECONCILIATION_ALREADY_FINALIZED if already finalized, and 422 RECONCILIATION_NOT_BALANCED"
                    + " (with the outstanding difference) if it does not balance.",
            tags = {"Bank Reconciliation"})
    @ApiResponse(
            responseCode = "200",
            description = "Reconciliation finalized",
            content = @Content(schema = @Schema(implementation = BankReconciliationResponse.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Caller lacks the accounting:reconciliation:adjust permission",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Reconciliation not found (RECONCILIATION_NOT_FOUND)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "Reconciliation already finalized (RECONCILIATION_ALREADY_FINALIZED)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "422",
            description = "Reconciliation does not balance (RECONCILIATION_NOT_BALANCED)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<BankReconciliationResponse> finalizeReconciliation(
            @Parameter(description = "Reconciliation id", required = true) @PathVariable UUID reconciliationId) {
        log.info("Finalize reconciliation {}", reconciliationId);
        return ResponseEntity.ok(bankReconciliationService.finalizeReconciliation(reconciliationId));
    }

    @GetMapping("/{reconciliationId}/report")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:reconciliation:view"})
    @PreAuthorize("hasAuthority('accounting:reconciliation:view')")
    @EmitEvent(id = "ACCOUNTING_RECONCILIATION_REPORT", apiVersion = "1")
    @Operation(
            summary = "Reconciliation report",
            operationId = "getReconciliationReport",
            description = "Returns the reconciliation report: opening (GL) and closing (statement) balances, matched"
                    + " vs outstanding lines, adjustments, and the outstanding difference. Returns 404"
                    + " RECONCILIATION_NOT_FOUND if the id is unknown.",
            tags = {"Bank Reconciliation"})
    @ApiResponse(
            responseCode = "200",
            description = "Report generated",
            content = @Content(schema = @Schema(implementation = ReconciliationReportResponse.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Caller lacks the accounting:reconciliation:view permission",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Reconciliation not found (RECONCILIATION_NOT_FOUND)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<ReconciliationReportResponse> getReconciliationReport(
            @Parameter(description = "Reconciliation id", required = true) @PathVariable UUID reconciliationId) {
        return ResponseEntity.ok(bankReconciliationService.report(reconciliationId));
    }

    @GetMapping("/{reconciliationId}/audit")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:reconciliation:view"})
    @PreAuthorize("hasAuthority('accounting:reconciliation:view')")
    @EmitEvent(id = "ACCOUNTING_RECONCILIATION_AUDIT", apiVersion = "1")
    @Operation(
            summary = "Reconciliation audit trail",
            operationId = "getReconciliationAudit",
            description = "Returns the audit trail of a reconciliation's actions (import, matches, adjustments,"
                    + " finalize), ordered by time. Returns 404 RECONCILIATION_NOT_FOUND if the id is unknown.",
            tags = {"Bank Reconciliation"})
    @ApiResponse(
            responseCode = "200",
            description = "Audit trail generated",
            content = @Content(schema = @Schema(implementation = ReconciliationAuditResponse.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Caller lacks the accounting:reconciliation:view permission",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Reconciliation not found (RECONCILIATION_NOT_FOUND)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<ReconciliationAuditResponse> getReconciliationAudit(
            @Parameter(description = "Reconciliation id", required = true) @PathVariable UUID reconciliationId) {
        return ResponseEntity.ok(bankReconciliationService.audit(reconciliationId));
    }
}
