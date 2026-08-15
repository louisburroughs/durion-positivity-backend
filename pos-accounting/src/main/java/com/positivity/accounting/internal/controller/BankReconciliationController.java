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
import com.positivity.accounting.internal.security.AccountingPermissions;
import com.positivity.accounting.service.BankReconciliationService;
import com.positivity.events.EmitEvent;
import com.positivity.shared.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
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
 * REST controller for the manual CSV bank reconciliation workflow (Story F2,
 * issue
 * #965, decisions D-5/D-6): import a bank statement CSV for a reconcilable GL
 * cash
 * account, match statement lines to posted GL journal-entry lines, record
 * adjustments (which post real balanced journal entries through the
 * accounting-period
 * gate), and finalize only when the statement and GL ending balances agree.
 *
 * <p>
 * Reads require {@code accounting:reconciliation:view}; mutations require
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
    @PreAuthorize("hasAuthority('" + AccountingPermissions.RECONCILIATION_ADJUST + "')")
    @EmitEvent(id = "ACCOUNTING_RECONCILIATION_IMPORT", apiVersion = "1")
    @Operation(
            operationId = "importReconciliation",
            summary = "Import Bank Statement CSV",
            description = """
                    Parses a bank statement CSV (columns: date, description, signed amount, reference) for a \
                    reconcilable GL cash account and creates an IN_PROGRESS reconciliation whose statement \
                    lines start UNMATCHED, snapshotting the GL ending balance from posted journal-entry lines \
                    as of the statement date.
                    Use this tool to start a reconciliation cycle; do not use matchReconciliation, \
                    addReconciliationAdjustment or finalizeReconciliation, which operate on a reconciliation \
                    that already exists.
                    Preconditions: the GL account must exist with its reconcilable flag set to true.
                    Required inputs: glAccountId (UUID), periodStartDate, periodEndDate, statementDate, \
                    statementEndingBalance, currency (3-letter ISO code) and the csv text itself.
                    Emits an ACCOUNTING_RECONCILIATION_IMPORT event.
                    Returns 422 ACCOUNT_NOT_RECONCILABLE when the account's reconcilable flag is false, and \
                    400 when the CSV is malformed.
                    """,
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
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Statement metadata plus the raw CSV text to import for the cash account.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "July statement import", value = """
                                                                    {"glAccountId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b",
                                                                     "periodStartDate":"2026-07-01",
                                                                     "periodEndDate":"2026-07-31",
                                                                     "statementDate":"2026-07-31",
                                                                     "statementEndingBalance":10250.75,
                                                                     "currency":"USD",
                                                                     "csv":"date,description,amount,reference\\n2026-07-02,Deposit,500.00,DEP-1\\n2026-07-05,Bank fee,-15.00,FEE-1"}
                                                                    """)))
                    @Valid
                    @RequestBody
                    @NonNull
                    BankReconciliationImportRequest request) {
        if (log.isInfoEnabled()) {
            log.info("Import bank reconciliation for account {}", sanitizeForLog(request.getGlAccountId()));
        }
        return ResponseEntity.ok(bankReconciliationService.importStatement(request));
    }

    @GetMapping("/adjustment-types")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:reconciliation:view"})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.RECONCILIATION_VIEW + "')")
    @EmitEvent(id = "ACCOUNTING_RECONCILIATION_ADJUSTMENT_TYPES_LIST", apiVersion = "1")
    @Operation(
            operationId = "listReconciliationAdjustmentTypes",
            summary = "List Reconciliation Adjustment Types",
            description = """
                    Returns the supported reconciliation adjustment types with their sign rules (BANK_FEE and \
                    NSF_FEE negative-only, INTEREST_EARNED positive-only, OTHER any), so clients never \
                    hardcode the enum.
                    Use this tool to populate an adjustment picker before calling \
                    addReconciliationAdjustment; do not use addReconciliationAdjustment itself just to \
                    discover the types.
                    Preconditions: none.
                    Required inputs: none; there are no parameters and no request body.
                    Emits an ACCOUNTING_RECONCILIATION_ADJUSTMENT_TYPES_LIST audit event; no state changes.
                    Returns 200 with the full static type list.
                    """,
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
    @PreAuthorize("hasAuthority('" + AccountingPermissions.RECONCILIATION_VIEW + "')")
    @EmitEvent(id = "ACCOUNTING_RECONCILIATION_LIST", apiVersion = "1")
    @Operation(
            operationId = "listReconciliations",
            summary = "List Reconciliations",
            description = """
                    Lists bank reconciliations most recent first as a paginated projection, optionally \
                    filtered by GL account and status.
                    Use this tool to find in-progress or finalized reconciliations; do not use \
                    getReconciliation, which fetches one reconciliation with its lines by id.
                    Preconditions: none beyond the caller holding accounting:reconciliation:view.
                    Required inputs: none; glAccountId and status (IN_PROGRESS, FINALIZED) are optional \
                    filters, page defaults to 0 and size to 20.
                    Emits an ACCOUNTING_RECONCILIATION_LIST audit event; no state changes.
                    Returns 200 with an empty page when nothing matches the filters.
                    """,
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
    @PreAuthorize("hasAuthority('" + AccountingPermissions.RECONCILIATION_VIEW + "')")
    @EmitEvent(id = "ACCOUNTING_RECONCILIATION_GET", apiVersion = "1")
    @Operation(
            operationId = "getReconciliation",
            summary = "Get Reconciliation",
            description = """
                    Returns one bank reconciliation with its imported statement lines, match state and \
                    adjustments.
                    Use this tool when the reconciliation id is already known; use listReconciliations \
                    instead when searching by account or status, or getReconciliationReport for the balance \
                    summary view.
                    Preconditions: the reconciliation must exist.
                    Required inputs: reconciliationId (UUID) as a path parameter; there is no request body.
                    Emits an ACCOUNTING_RECONCILIATION_GET audit event; no state changes.
                    Returns 404 RECONCILIATION_NOT_FOUND when the id is unknown.
                    """,
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
    @PreAuthorize("hasAuthority('" + AccountingPermissions.RECONCILIATION_ADJUST + "')")
    @EmitEvent(id = "ACCOUNTING_RECONCILIATION_MATCH", apiVersion = "1")
    @Operation(
            operationId = "matchReconciliation",
            summary = "Match Statement Lines To GL Lines",
            description = """
                    Matches a set of statement lines to a set of posted GL journal-entry lines on the \
                    reconciled account (1-to-1 or N-to-1), marking the statement lines MATCHED and recording \
                    the linkage.
                    Use this tool to pair bank activity with ledger activity; do not use \
                    unmatchReconciliation, which reverses a match, and use addReconciliationAdjustment for \
                    bank-only items like fees that have no GL counterpart yet.
                    Preconditions: the reconciliation must be IN_PROGRESS, every statement line must be \
                    UNMATCHED, every GL line must be POSTED and not already reconciled, and the two sets must \
                    net to equal signed amounts within 0.01.
                    Required inputs: reconciliationId (UUID) as a path parameter plus non-empty \
                    statementLineIds and glLineIds lists.
                    Emits an ACCOUNTING_RECONCILIATION_MATCH event; no journal entries are created by \
                    matching.
                    Returns 404 RECONCILIATION_NOT_FOUND when the reconciliation or a line is missing, 409 \
                    RECONCILIATION_ALREADY_FINALIZED or RECONCILIATION_LINE_INELIGIBLE for state conflicts, \
                    and 422 MATCH_AMOUNT_MISMATCH when the sets do not net.
                    """,
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
            @Parameter(description = "Reconciliation id", required = true) @PathVariable @NonNull UUID reconciliationId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description =
                                    "Statement-line and GL-line id sets to link; the sets must net to equal amounts.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "One-to-one match", value = """
                                                                    {"statementLineIds":["018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b"],
                                                                     "glLineIds":["018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5c"]}
                                                                    """)))
                    @Valid
                    @RequestBody
                    @NonNull
                    ReconciliationMatchRequest request) {
        if (log.isInfoEnabled()) {
            log.info("Match lines in reconciliation {}", sanitizeForLog(reconciliationId));
        }
        return ResponseEntity.ok(bankReconciliationService.match(reconciliationId, request));
    }

    @PostMapping("/{reconciliationId}/unmatch")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:reconciliation:adjust"})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.RECONCILIATION_ADJUST + "')")
    @EmitEvent(id = "ACCOUNTING_RECONCILIATION_UNMATCH", apiVersion = "1")
    @Operation(
            operationId = "unmatchReconciliation",
            summary = "Reverse A Reconciliation Match",
            description = """
                    Reverses a previously recorded match, returning the affected statement lines to UNMATCHED \
                    and releasing the linked GL lines for re-matching.
                    Use this tool to correct a wrong pairing while the reconciliation is still IN_PROGRESS; \
                    do not use matchReconciliation, which records new matches.
                    Preconditions: the reconciliation must not be FINALIZED, and either the matchId or the \
                    statementLineIds must resolve to exactly one match group.
                    Required inputs: reconciliationId (UUID) as a path parameter plus matchId or \
                    statementLineIds in the body (one of the two is required).
                    Emits an ACCOUNTING_RECONCILIATION_UNMATCH event.
                    Returns 404 RECONCILIATION_NOT_FOUND when the reconciliation or match group is missing, \
                    409 RECONCILIATION_ALREADY_FINALIZED when finalized, and 400 when neither identifier \
                    resolves to a single match group.
                    """,
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
            @Parameter(description = "Reconciliation id", required = true) @PathVariable @NonNull UUID reconciliationId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Match group to reverse, identified by matchId or by its statement line ids.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(
                                                            name = "Unmatch by match id",
                                                            value =
                                                                    "{\"matchId\":\"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5d\"}")))
                    @Valid
                    @RequestBody
                    @NonNull
                    ReconciliationUnmatchRequest request) {
        if (log.isInfoEnabled()) {
            log.info("Unmatch in reconciliation {}", sanitizeForLog(reconciliationId));
        }
        return ResponseEntity.ok(bankReconciliationService.unmatch(reconciliationId, request));
    }

    @PostMapping("/{reconciliationId}/adjustments")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:reconciliation:adjust"})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.RECONCILIATION_ADJUST + "')")
    @EmitEvent(id = "ACCOUNTING_RECONCILIATION_ADJUSTMENT", apiVersion = "1")
    @Operation(
            operationId = "addReconciliationAdjustment",
            summary = "Record Reconciliation Adjustment",
            description = """
                    Records a signed reconciliation adjustment and posts a real balanced journal entry, \
                    debiting or crediting the reconciled cash account against the type's mapped counter \
                    account, through the accounting-period gate.
                    Use this tool for bank-only items such as fees or interest that have no GL counterpart; \
                    do not use matchReconciliation, which links existing posted GL lines.
                    Preconditions: the reconciliation must be IN_PROGRESS, and the amount sign must be \
                    permitted for the type (BANK_FEE and NSF_FEE negative, INTEREST_EARNED positive, OTHER \
                    any).
                    Required inputs: reconciliationId (UUID) as a path parameter, type (BANK_FEE, NSF_FEE, \
                    INTEREST_EARNED or OTHER) and a non-zero signed amount; description (max 500 chars) is \
                    optional.
                    Emits an ACCOUNTING_RECONCILIATION_ADJUSTMENT event and posts a journal entry that \
                    changes GL balances.
                    Returns 404 RECONCILIATION_NOT_FOUND when the reconciliation is missing, 409 \
                    RECONCILIATION_ALREADY_FINALIZED when finalized, and 422 PERIOD_CLOSED, \
                    PERIOD_HARD_LOCKED or RECONCILIATION_ADJUSTMENT_SIGN_INVALID for period-gate or sign \
                    failures.
                    """,
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
            @Parameter(description = "Reconciliation id", required = true) @PathVariable @NonNull UUID reconciliationId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Typed, signed adjustment that will post a balanced journal entry.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Bank fee", value = """
                                                                    {"type":"BANK_FEE",
                                                                     "amount":-15.00,
                                                                     "description":"Monthly account service fee"}
                                                                    """)))
                    @Valid
                    @RequestBody
                    @NonNull
                    ReconciliationAdjustmentRequest request) {
        if (log.isInfoEnabled()) {
            log.info(
                    "Record {} adjustment on reconciliation {}",
                    sanitizeForLog(request.getType()),
                    sanitizeForLog(reconciliationId));
        }
        return ResponseEntity.ok(bankReconciliationService.addAdjustment(reconciliationId, request));
    }

    @PostMapping("/{reconciliationId}/finalize")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:reconciliation:adjust"})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.RECONCILIATION_ADJUST + "')")
    @EmitEvent(id = "ACCOUNTING_RECONCILIATION_FINALIZE", apiVersion = "1")
    @Operation(
            operationId = "finalizeReconciliation",
            summary = "Finalize Reconciliation",
            description = """
                    Finalizes a reconciliation (IN_PROGRESS to FINALIZED), locking it against further \
                    matching, unmatching or adjustments.
                    Use this tool once all lines are matched or adjusted; do not use it while a difference \
                    remains, which addReconciliationAdjustment or further matching must clear first.
                    Preconditions: the statement ending balance must equal the GL ending balance plus the \
                    sum of adjustments within 0.01, matched GL lines being already reflected in the GL \
                    ending balance.
                    Required inputs: reconciliationId (UUID) as a path parameter; there is no request body.
                    Emits an ACCOUNTING_RECONCILIATION_FINALIZE event; FINALIZED is terminal for the \
                    reconciliation.
                    Returns 404 RECONCILIATION_NOT_FOUND when missing, 409 RECONCILIATION_ALREADY_FINALIZED \
                    when already finalized, and 422 RECONCILIATION_NOT_BALANCED carrying the outstanding \
                    difference as a field error when it does not balance.
                    """,
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
            @Parameter(description = "Reconciliation id", required = true) @PathVariable @NonNull
                    UUID reconciliationId) {
        if (log.isInfoEnabled()) {
            log.info("Finalize reconciliation {}", sanitizeForLog(reconciliationId));
        }
        return ResponseEntity.ok(bankReconciliationService.finalizeReconciliation(reconciliationId));
    }

    private static String sanitizeForLog(@Nullable Object value) {
        if (value == null) {
            return "null";
        }
        return value.toString().replace('\n', '_').replace('\r', '_');
    }

    @GetMapping("/{reconciliationId}/report")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:reconciliation:view"})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.RECONCILIATION_VIEW + "')")
    @EmitEvent(id = "ACCOUNTING_RECONCILIATION_REPORT", apiVersion = "1")
    @Operation(
            operationId = "getReconciliationReport",
            summary = "Get Reconciliation Report",
            description = """
                    Returns the reconciliation report: opening GL and closing statement balances, matched \
                    versus outstanding lines, adjustments and the outstanding difference.
                    Use this tool to see how far a reconciliation is from balancing before \
                    finalizeReconciliation; use getReconciliation instead for the raw line-level detail.
                    Preconditions: the reconciliation must exist.
                    Required inputs: reconciliationId (UUID) as a path parameter; there is no request body.
                    Emits an ACCOUNTING_RECONCILIATION_REPORT audit event; no state changes.
                    Returns 404 RECONCILIATION_NOT_FOUND when the id is unknown.
                    """,
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
    @PreAuthorize("hasAuthority('" + AccountingPermissions.RECONCILIATION_VIEW + "')")
    @EmitEvent(id = "ACCOUNTING_RECONCILIATION_AUDIT", apiVersion = "1")
    @Operation(
            operationId = "getReconciliationAudit",
            summary = "Get Reconciliation Audit Trail",
            description = """
                    Returns the time-ordered audit trail of a reconciliation's actions: import, matches, \
                    unmatches, adjustments and finalize, each with the acting user.
                    Use this tool when reviewing who did what during a reconciliation; use \
                    getReconciliationReport instead for the balance summary.
                    Preconditions: the reconciliation must exist.
                    Required inputs: reconciliationId (UUID) as a path parameter; there is no request body.
                    Emits an ACCOUNTING_RECONCILIATION_AUDIT audit event; no state changes.
                    Returns 404 RECONCILIATION_NOT_FOUND when the id is unknown.
                    """,
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
