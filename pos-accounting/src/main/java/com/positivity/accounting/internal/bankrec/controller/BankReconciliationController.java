package com.positivity.accounting.internal.bankrec.controller;

import com.positivity.accounting.internal.bankrec.dto.AdjustmentTypeResponse;
import com.positivity.accounting.internal.bankrec.dto.BankReconciliationListResponse;
import com.positivity.accounting.internal.bankrec.dto.BankReconciliationResponse;
import com.positivity.accounting.internal.bankrec.dto.ReconciliationApiStatus;
import com.positivity.accounting.internal.bankrec.dto.ReconciliationAuditResponse;
import com.positivity.accounting.internal.bankrec.dto.ReconciliationCreateRequest;
import com.positivity.accounting.internal.bankrec.dto.ReconciliationJustificationRequest;
import com.positivity.accounting.internal.bankrec.dto.ReconciliationReasonRequest;
import com.positivity.accounting.internal.bankrec.dto.ReconciliationReportResponse;
import com.positivity.accounting.internal.bankrec.dto.ReconciliationReviewResponse;
import com.positivity.accounting.internal.bankrec.dto.ReconciliationTransitionRequest;
import com.positivity.accounting.internal.bankrec.enums.BankAdjustmentType;
import com.positivity.accounting.internal.bankrec.service.BankReconciliationService;
import com.positivity.accounting.internal.bankrec.service.ReconciliationApprovalService;
import com.positivity.accounting.internal.bankrec.service.ReconciliationListFilter;
import com.positivity.accounting.internal.bankrec.service.ReconciliationReviewService;
import com.positivity.accounting.internal.security.AccountingPermissions;
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
import java.time.LocalDate;
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
import org.springframework.http.HttpStatus;
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
 * REST controller for the bank reconciliation header and its approval workflow (Story F2 #965; S4 #2303; S5
 * #2304): create from a committed statement, read, list, review, report and audit; submit (preparer), approve
 * through the kept {@code /finalize} path, return, cancel and supersede (approver).
 *
 * <p>Reads require {@code accounting:reconciliation:view}; create and submit {@code
 * accounting:reconciliation:adjust}; approve, return, cancel and supersede {@code
 * accounting:reconciliation:approve} — the separation-of-duties key (D3).
 */
@RestController
@RequestMapping("/v1/accounting/reconciliations")
@Tag(
        name = "Bank Reconciliation",
        description = "Manual bank reconciliation: match statement lines to posted GL entries,"
                + " record adjustments, and finalize when balanced.")
@RequiredArgsConstructor
@Validated
public class BankReconciliationController {

    private static final Logger log = LoggerFactory.getLogger(BankReconciliationController.class);

    /** The largest audit page served (§4.9). */
    private static final int MAX_AUDIT_PAGE = 200;

    private final BankReconciliationService bankReconciliationService;
    private final ReconciliationApprovalService approvalService;
    private final ReconciliationReviewService reviewService;

    @PostMapping
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:reconciliation:adjust"})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.RECONCILIATION_ADJUST + "')")
    @EmitEvent(id = "ACCOUNTING_RECONCILIATION_CREATE", apiVersion = "1")
    @Operation(
            operationId = "createReconciliation",
            summary = "Start Reconciliation From Statement",
            description = """
                    Starts an IN_PROGRESS reconciliation of a COMMITTED bank statement: the window, opening and \
                    closing balances are copied from the statement, and the explicit equation (E3), the opening \
                    terms, the account baseline and the unexplained counts are computed live from the ledger.
                    Use this tool to begin reconciling a statement committed through bank-statements or a file \
                    import; do not use createBankStatement or the bank-import commit, which commit the statement \
                    itself.
                    Preconditions: the account must be a reconcilable BANK_CASH account; the statement must be \
                    COMMITTED on that account and have no IN_PROGRESS reconciliation and no FINALIZED one without \
                    a successor. An interim reconciliation to a date is a manual-entry statement in phase 1.
                    Required inputs: glAccountId, requestId (UUIDv7) and statementId in the body.
                    Emits an ACCOUNTING_RECONCILIATION_CREATE event and writes a RECONCILIATION_CREATE audit row; \
                    no journal entry is posted.
                    Returns 201 with the header, or 200 with replayed true when the same requestId and payload \
                    are sent again; 409 RECONCILIATION_WINDOW_ALREADY_RECONCILED (fieldErrors naming the \
                    reconciliationId) when the statement is already reconciled, 409 IDEMPOTENCY_CONFLICT when the \
                    requestId was used with another payload, 404 BANK_STATEMENT_NOT_FOUND when the statement is \
                    unknown on the account, 422 ACCOUNT_NOT_RECONCILABLE when the account is not a bank account, \
                    and 422 BANK_ACCOUNT_FEED_NOT_LINKED when the body has no statementId (the statementless \
                    interim is phase 2).
                    """,
            tags = {"Bank Reconciliation"})
    @ApiResponse(
            responseCode = "201",
            description = "Reconciliation started",
            content = @Content(schema = @Schema(implementation = BankReconciliationResponse.class)))
    @ApiResponse(
            responseCode = "200",
            description = "Replay of an earlier create with the same requestId (replayed = true)",
            content = @Content(schema = @Schema(implementation = BankReconciliationResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description = "Request body invalid (VALIDATION_ERROR)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Caller lacks the accounting:reconciliation:adjust permission",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Statement not found on the account (BANK_STATEMENT_NOT_FOUND)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "Statement already reconciled (RECONCILIATION_WINDOW_ALREADY_RECONCILED) or requestId"
                    + " reused with another payload (IDEMPOTENCY_CONFLICT)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "422",
            description = "Account is not a reconcilable bank account (ACCOUNT_NOT_RECONCILABLE), or the body is"
                    + " statementless on an account without a feed link (BANK_ACCOUNT_FEED_NOT_LINKED)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<BankReconciliationResponse> createReconciliation(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "The statement to reconcile and the command's requestId.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "September statement", value = """
                                                                    {"glAccountId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b",
                                                                     "requestId":"019a0000-0000-7000-8000-000000000001",
                                                                     "statementId":"019a0000-0000-7000-8000-000000000002"}
                                                                    """)))
                    @Valid
                    @RequestBody
                    @NonNull
                    ReconciliationCreateRequest request) {
        if (log.isInfoEnabled()) {
            log.info(
                    "Create reconciliation of statement {} on account {}",
                    sanitizeForLog(request.getStatementId()),
                    sanitizeForLog(request.getGlAccountId()));
        }
        BankReconciliationResponse response = bankReconciliationService.create(request);
        return ResponseEntity.status(response.isReplayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(response);
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
                    NSF_FEE negative-only, INTEREST_EARNED positive-only, OTHER and TRANSFER any), so clients \
                    never hardcode the enum.
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
                    Lists bank reconciliation headers most recent first as a paginated projection, optionally \
                    filtered by GL account, status, attribution period (periodCode, YYYY-MM) and a from/to \
                    window on the statement end date; each row carries the terms its last mutation stored.
                    Use this tool to find reconciliations by account, status or period; do not use \
                    getReconciliation, which fetches one reconciliation with its lines by id.
                    Preconditions: none beyond the caller holding accounting:reconciliation:view.
                    Required inputs: none; glAccountId, status (IN_PROGRESS, SUBMITTED, FINALIZED, INVALIDATED, \
                    SUPERSEDED, CANCELLED), periodCode, from and \
                    to are optional filters, page defaults to 0 and size to 20.
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
                    ReconciliationApiStatus status,
            @Parameter(
                            description = "Filter by attribution period (YYYY-MM of the statement end date)",
                            example = "2026-09")
                    @RequestParam(required = false)
                    String periodCode,
            @Parameter(description = "Statement end date on or after this date", example = "2026-01-01")
                    @RequestParam(required = false)
                    LocalDate from,
            @Parameter(description = "Statement end date on or before this date", example = "2026-12-31")
                    @RequestParam(required = false)
                    LocalDate to,
            @Parameter(description = "Zero-based page index", example = "0") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size", example = "20") @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(
                page,
                size,
                Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "reconciliationId")));
        ReconciliationListFilter filter = new ReconciliationListFilter(
                glAccountId, status != null ? status.toDomain() : null, periodCode, from, to);
        return ResponseEntity.ok(bankReconciliationService.list(filter, pageable));
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
                    Returns one bank reconciliation header with every term of the explicit equation (E3) and \
                    the opening terms computed live from the ledger, the baseline date and the unexplained \
                    counts; statement lines are not embedded (read them from bank-transactions).
                    Use this tool when the reconciliation id is already known; use listReconciliations \
                    instead when searching by account or status, or getReconciliationReview for the full \
                    workspace read model.
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

    @PostMapping("/{reconciliationId}/submit")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:reconciliation:adjust"})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.RECONCILIATION_ADJUST + "')")
    @EmitEvent(id = "ACCOUNTING_RECONCILIATION_SUBMIT", apiVersion = "1")
    @Operation(
            operationId = "submitReconciliation",
            summary = "Submit Reconciliation For Approval",
            description = """
                    Submits an IN_PROGRESS reconciliation for approval (IN_PROGRESS to SUBMITTED) after the \
                    approval gate E4 holds on the live figures: the difference within 0.01, then no unexplained \
                    bank transaction and no unexplained ledger line from the window's baseline to its end. The \
                    opening difference is never a condition.
                    Use this tool when the preparer has explained every item; use finalizeReconciliation for the \
                    approver's step, and getReconciliationReview to see what still blocks (readiness.canSubmit).
                    Preconditions: the reconciliation must be IN_PROGRESS; while SUBMITTED it no longer changes \
                    until the approver returns it.
                    Required inputs: reconciliationId (UUID) as a path parameter; the body is optional and may \
                    carry the version the caller read.
                    Emits an ACCOUNTING_RECONCILIATION_SUBMIT event, writes a RECONCILIATION_SUBMIT audit row and \
                    queues the accounting.bankreconciliation.submitted fact; no journal entry is posted.
                    Returns 200 with the header; 404 RECONCILIATION_NOT_FOUND; 409 \
                    RECONCILIATION_ALREADY_FINALIZED, RECONCILIATION_NOT_EDITABLE (SUBMITTED, INVALIDATED, \
                    SUPERSEDED, CANCELLED) or OPTIMISTIC_LOCK; 422 RECONCILIATION_NOT_BALANCED \
                    (fieldErrors[difference]) or, when it balances, RECONCILIATION_HAS_UNEXPLAINED_ITEMS \
                    (fieldErrors countUnexplainedBank, countUnexplainedLedger and the first 50 ids per side).
                    """,
            tags = {"Bank Reconciliation"})
    @ApiResponse(
            responseCode = "200",
            description = "Reconciliation submitted",
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
            description = "RECONCILIATION_ALREADY_FINALIZED, RECONCILIATION_NOT_EDITABLE or OPTIMISTIC_LOCK",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "422",
            description = "RECONCILIATION_NOT_BALANCED or RECONCILIATION_HAS_UNEXPLAINED_ITEMS",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<BankReconciliationResponse> submitReconciliation(
            @Parameter(description = "Reconciliation id", required = true) @PathVariable @NonNull UUID reconciliationId,
            @RequestBody(required = false) @Nullable ReconciliationTransitionRequest request) {
        if (log.isInfoEnabled()) {
            log.info("Submit reconciliation {}", sanitizeForLog(reconciliationId));
        }
        return ResponseEntity.ok(approvalService.submit(reconciliationId, request));
    }

    @PostMapping("/{reconciliationId}/finalize")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:reconciliation:approve"})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.RECONCILIATION_APPROVE + "')")
    @EmitEvent(id = "ACCOUNTING_RECONCILIATION_FINALIZE", apiVersion = "1")
    @Operation(
            operationId = "finalizeReconciliation",
            summary = "Approve Reconciliation",
            description = """
                    Approves a SUBMITTED reconciliation (SUBMITTED to FINALIZED): the row is locked, the live \
                    ledger balance, every term of E3, the baseline and both unexplained counts are recomputed \
                    and the gate E4 is evaluated again, so nothing is approved on a stale figure. On success the \
                    approvedGlEndingBalance and baselineDate are snapshotted, the matches are sealed, and a \
                    reconciliation it corrects becomes SUPERSEDED.
                    Use this tool as the approver once the preparer has submitted; use submitReconciliation for \
                    the preparer's step and returnReconciliation to send it back instead.
                    Preconditions: the reconciliation must be SUBMITTED, and the approver must not be the \
                    submitter unless the tenant's BANK_REC_ALLOW_SELF_APPROVAL is true (every approval under \
                    that switch is audited as a self-approval).
                    Required inputs: reconciliationId (UUID) as a path parameter; the body is optional and may \
                    carry the version the caller read.
                    Emits an ACCOUNTING_RECONCILIATION_FINALIZE event, writes a RECONCILIATION_APPROVE audit row \
                    and queues accounting.bankreconciliation.approved (and .superseded for a corrected \
                    predecessor); FINALIZED is terminal for the reconciliation.
                    Returns 200 with the header; 403 RECONCILIATION_SELF_APPROVAL (audited) when the submitter \
                    approves without the switch; 404 RECONCILIATION_NOT_FOUND; 409 RECONCILIATION_NOT_SUBMITTED, \
                    RECONCILIATION_ALREADY_FINALIZED or OPTIMISTIC_LOCK; 422 RECONCILIATION_NOT_BALANCED \
                    (fieldErrors[difference]) or RECONCILIATION_HAS_UNEXPLAINED_ITEMS.
                    """,
            tags = {"Bank Reconciliation"})
    @ApiResponse(
            responseCode = "200",
            description = "Reconciliation approved (FINALIZED)",
            content = @Content(schema = @Schema(implementation = BankReconciliationResponse.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Caller lacks the accounting:reconciliation:approve permission, or submitted it"
                    + " (RECONCILIATION_SELF_APPROVAL)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Reconciliation not found (RECONCILIATION_NOT_FOUND)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "RECONCILIATION_NOT_SUBMITTED, RECONCILIATION_ALREADY_FINALIZED or OPTIMISTIC_LOCK",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "422",
            description = "RECONCILIATION_NOT_BALANCED or RECONCILIATION_HAS_UNEXPLAINED_ITEMS",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<BankReconciliationResponse> finalizeReconciliation(
            @Parameter(description = "Reconciliation id", required = true) @PathVariable @NonNull UUID reconciliationId,
            @RequestBody(required = false) @Nullable ReconciliationTransitionRequest request) {
        if (log.isInfoEnabled()) {
            log.info("Approve reconciliation {}", sanitizeForLog(reconciliationId));
        }
        return ResponseEntity.ok(approvalService.approve(reconciliationId, request));
    }

    @PostMapping("/{reconciliationId}/return")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:reconciliation:approve"})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.RECONCILIATION_APPROVE + "')")
    @EmitEvent(id = "ACCOUNTING_RECONCILIATION_RETURN", apiVersion = "1")
    @Operation(
            operationId = "returnReconciliation",
            summary = "Return Reconciliation To Preparer",
            description = """
                    Returns a SUBMITTED reconciliation to its preparer (SUBMITTED to IN_PROGRESS) with the \
                    approver's reason, so it can change again.
                    Use this tool when the approver will not approve as submitted; use cancelReconciliation to \
                    abandon it and finalizeReconciliation to approve it.
                    Preconditions: the reconciliation must be SUBMITTED.
                    Required inputs: reconciliationId (UUID) as a path parameter and reason (at least 10 \
                    characters) in the body; version is optional.
                    Emits an ACCOUNTING_RECONCILIATION_RETURN event and writes a RECONCILIATION_RETURN audit row \
                    with the reason; no fact is published.
                    Returns 200 with the header; 400 VALIDATION_ERROR (blank reason) or JUSTIFICATION_REQUIRED \
                    (shorter than 10); 404 RECONCILIATION_NOT_FOUND; 409 RECONCILIATION_NOT_SUBMITTED or \
                    OPTIMISTIC_LOCK.
                    """,
            tags = {"Bank Reconciliation"})
    @ApiResponse(
            responseCode = "200",
            description = "Reconciliation returned (IN_PROGRESS)",
            content = @Content(schema = @Schema(implementation = BankReconciliationResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description = "VALIDATION_ERROR or JUSTIFICATION_REQUIRED",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Caller lacks the accounting:reconciliation:approve permission",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Reconciliation not found (RECONCILIATION_NOT_FOUND)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "RECONCILIATION_NOT_SUBMITTED or OPTIMISTIC_LOCK",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<BankReconciliationResponse> returnReconciliation(
            @Parameter(description = "Reconciliation id", required = true) @PathVariable @NonNull UUID reconciliationId,
            @Valid @RequestBody @NonNull ReconciliationReasonRequest request) {
        if (log.isInfoEnabled()) {
            log.info("Return reconciliation {}", sanitizeForLog(reconciliationId));
        }
        return ResponseEntity.ok(approvalService.returnToPreparer(reconciliationId, request));
    }

    @PostMapping("/{reconciliationId}/cancel")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:reconciliation:approve"})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.RECONCILIATION_APPROVE + "')")
    @EmitEvent(id = "ACCOUNTING_RECONCILIATION_CANCEL", apiVersion = "1")
    @Operation(
            operationId = "cancelReconciliation",
            summary = "Cancel Reconciliation",
            description = """
                    Cancels an IN_PROGRESS or SUBMITTED reconciliation (to CANCELLED): its proposed and accepted \
                    matches become UNMATCHED with unmatchReason RECONCILIATION_CANCELLED (bank rows back to \
                    UNMATCHED), the OPEN outstanding items it registered are RELEASED, and posted adjustments \
                    stay posted — they are real journal entries, reversed explicitly if wrong.
                    Use this tool to abandon a reconciliation; use returnReconciliation to send a submitted one \
                    back, and supersedeReconciliation to correct an approved one.
                    Preconditions: the reconciliation must be IN_PROGRESS or SUBMITTED.
                    Required inputs: reconciliationId (UUID) as a path parameter and justification (at least \
                    10 characters) in the body; version is optional.
                    Emits an ACCOUNTING_RECONCILIATION_CANCEL event, writes a RECONCILIATION_CANCEL audit row \
                    and queues accounting.bankreconciliation.cancelled; CANCELLED is terminal.
                    Returns 200 with the header; 400 VALIDATION_ERROR or JUSTIFICATION_REQUIRED; 404 \
                    RECONCILIATION_NOT_FOUND; 409 RECONCILIATION_ALREADY_FINALIZED, RECONCILIATION_NOT_EDITABLE \
                    or OPTIMISTIC_LOCK.
                    """,
            tags = {"Bank Reconciliation"})
    @ApiResponse(
            responseCode = "200",
            description = "Reconciliation cancelled",
            content = @Content(schema = @Schema(implementation = BankReconciliationResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description = "VALIDATION_ERROR or JUSTIFICATION_REQUIRED",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Caller lacks the accounting:reconciliation:approve permission",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Reconciliation not found (RECONCILIATION_NOT_FOUND)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "RECONCILIATION_ALREADY_FINALIZED, RECONCILIATION_NOT_EDITABLE or OPTIMISTIC_LOCK",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<BankReconciliationResponse> cancelReconciliation(
            @Parameter(description = "Reconciliation id", required = true) @PathVariable @NonNull UUID reconciliationId,
            @Valid @RequestBody @NonNull ReconciliationJustificationRequest request) {
        if (log.isInfoEnabled()) {
            log.info("Cancel reconciliation {}", sanitizeForLog(reconciliationId));
        }
        return ResponseEntity.ok(approvalService.cancel(reconciliationId, request));
    }

    @PostMapping("/{reconciliationId}/supersede")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:reconciliation:approve"})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.RECONCILIATION_APPROVE + "')")
    @EmitEvent(id = "ACCOUNTING_RECONCILIATION_SUPERSEDE", apiVersion = "1")
    @Operation(
            operationId = "supersedeReconciliation",
            summary = "Supersede Reconciliation",
            description = """
                    Starts a new IN_PROGRESS reconciliation of the same statement that supersedes a FINALIZED or \
                    INVALIDATED one: the predecessor's match members are released (its matches keep their state \
                    as sealed history) and re-proposed in the successor as PROPOSED matches for the preparer to \
                    re-confirm; OPEN outstanding items carry over unchanged. The successor uses the baseline \
                    that governed the window. When the successor is approved the predecessor becomes SUPERSEDED.
                    Use this tool to correct an approved window (there is no reopen); use createReconciliation \
                    for a statement never reconciled, and a corrected re-import (supersedesStatementId) when the \
                    bank's statement itself was wrong.
                    Preconditions: the reconciliation must be FINALIZED or INVALIDATED, not already superseded, \
                    and its statement still COMMITTED.
                    Required inputs: reconciliationId (UUID) as a path parameter and justification (at least \
                    10 characters) in the body; requestId (UUIDv7) makes a retry return the same successor; \
                    version is optional.
                    Emits an ACCOUNTING_RECONCILIATION_SUPERSEDE event and writes a RECONCILIATION_SUPERSEDE \
                    audit row on the predecessor and a RECONCILIATION_CREATE row on the successor.
                    Returns 201 with the successor's header (200 with replayed true on a replay); 400 \
                    VALIDATION_ERROR or JUSTIFICATION_REQUIRED; 404 RECONCILIATION_NOT_FOUND; 409 \
                    RECONCILIATION_WINDOW_ALREADY_RECONCILED (it is IN_PROGRESS or SUBMITTED, or the statement \
                    has another active one), RECONCILIATION_NOT_EDITABLE (SUPERSEDED, CANCELLED, already \
                    superseded, or its statement superseded), IDEMPOTENCY_CONFLICT or OPTIMISTIC_LOCK.
                    """,
            tags = {"Bank Reconciliation"})
    @ApiResponse(
            responseCode = "201",
            description = "Superseding reconciliation started",
            content = @Content(schema = @Schema(implementation = BankReconciliationResponse.class)))
    @ApiResponse(
            responseCode = "200",
            description = "Replay of an earlier supersede with the same requestId (replayed = true)",
            content = @Content(schema = @Schema(implementation = BankReconciliationResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description = "VALIDATION_ERROR or JUSTIFICATION_REQUIRED",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Caller lacks the accounting:reconciliation:approve permission",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Reconciliation not found (RECONCILIATION_NOT_FOUND)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "RECONCILIATION_WINDOW_ALREADY_RECONCILED, RECONCILIATION_NOT_EDITABLE,"
                    + " IDEMPOTENCY_CONFLICT or OPTIMISTIC_LOCK",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<BankReconciliationResponse> supersedeReconciliation(
            @Parameter(description = "Reconciliation id", required = true) @PathVariable @NonNull UUID reconciliationId,
            @Valid @RequestBody @NonNull ReconciliationJustificationRequest request) {
        if (log.isInfoEnabled()) {
            log.info("Supersede reconciliation {}", sanitizeForLog(reconciliationId));
        }
        BankReconciliationResponse response = approvalService.supersede(reconciliationId, request);
        return ResponseEntity.status(response.isReplayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(response);
    }

    private static String sanitizeForLog(@Nullable Object value) {
        if (value == null) {
            return "null";
        }
        return value.toString().replace('\n', '_').replace('\r', '_');
    }

    @GetMapping("/{reconciliationId}/review")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:reconciliation:view"})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.RECONCILIATION_VIEW + "')")
    @EmitEvent(id = "ACCOUNTING_RECONCILIATION_REVIEW", apiVersion = "1")
    @Operation(
            operationId = "getReconciliationReview",
            summary = "Get Reconciliation Review",
            description = """
                    Returns the review read model in one call, computed live, so a client never does arithmetic: \
                    the header (account, window, baseline and whether this statement set it, provenance, status, \
                    preparer, period state, version); every term of the explicit equation E3 with its \
                    drill-down, including late adjustments with their owning reconciliation; the opening terms \
                    and the OPENING_DIFFERENCE diagnostic, which never blocks; everything unresolved from the \
                    baseline on (late arrivals first, unexplained bank rows with their top ledger candidate, \
                    unexplained ledger lines with their top bank candidate, possible duplicates with their \
                    near-duplicate candidates, aged timing items awaiting reaffirmation, proposed and broken \
                    matches); the posted adjustments; the evidence (matches with their served residual, items, \
                    exclusions, the adjustments to clearing, the statement); and the readiness with its reasons.
                    Use this tool to render or audit the reconciliation workspace; use getReconciliation for the \
                    header alone and getReconciliationReport for the printable report.
                    Preconditions: the reconciliation must exist.
                    Required inputs: reconciliationId (UUID) as a path parameter; there is no request body.
                    Emits an ACCOUNTING_RECONCILIATION_REVIEW event; no state changes.
                    Returns 404 RECONCILIATION_NOT_FOUND when the id is unknown.
                    """,
            tags = {"Bank Reconciliation"})
    @ApiResponse(
            responseCode = "200",
            description = "Review read",
            content = @Content(schema = @Schema(implementation = ReconciliationReviewResponse.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Caller lacks the accounting:reconciliation:view permission",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Reconciliation not found (RECONCILIATION_NOT_FOUND)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<ReconciliationReviewResponse> getReconciliationReview(
            @Parameter(description = "Reconciliation id", required = true) @PathVariable UUID reconciliationId) {
        return ResponseEntity.ok(reviewService.review(reconciliationId));
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
                    Returns the reconciliation report: the statement lines matched versus outstanding, every \
                    term of the explicit equation E3 and the opening terms, the outstanding items with their \
                    age, the unexplained counts and sums, the adjustments and the adjustments to clearing, and \
                    the live difference.
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
                    Returns the stored audit trail, oldest first and a page at a time: every AccountingAuditLog row \
                    of the reconciliation (create, adjustments and their reversals, auto-match, submit, approve \
                    including a refused or allowed self-approval, return, cancel, supersede, invalidation), of its \
                    matches (match, accept, reject, unmatch) and of the outstanding items it registered, cleared \
                    or reaffirmed — each with operation, actor, timestamp, trace id, justification and the old and \
                    new value.
                    Use this tool when reviewing who did what during a reconciliation; use \
                    getReconciliationReport instead for the balance summary.
                    Preconditions: the reconciliation must exist.
                    Required inputs: reconciliationId (UUID) as a path parameter; page (default 0) and size \
                    (default 50, at most 200) are optional; there is no request body.
                    Emits an ACCOUNTING_RECONCILIATION_AUDIT audit event; no state changes.
                    Returns 404 RECONCILIATION_NOT_FOUND when the id is unknown.
                    """,
            tags = {"Bank Reconciliation"})
    @ApiResponse(
            responseCode = "200",
            description = "Audit trail page read",
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
            @Parameter(description = "Reconciliation id", required = true) @PathVariable UUID reconciliationId,
            @Parameter(description = "Zero-based page index", example = "0") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size (at most 200)", example = "50") @RequestParam(defaultValue = "50")
                    int size) {
        Pageable pageable = PageRequest.of(
                Math.max(page, 0),
                Math.clamp(size, 1, MAX_AUDIT_PAGE),
                Sort.by(Sort.Direction.ASC, "timestamp").and(Sort.by(Sort.Direction.ASC, "auditLogId")));
        return ResponseEntity.ok(bankReconciliationService.audit(reconciliationId, pageable));
    }
}
