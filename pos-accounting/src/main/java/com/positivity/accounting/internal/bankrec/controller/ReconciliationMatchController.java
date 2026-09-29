package com.positivity.accounting.internal.bankrec.controller;

import com.positivity.accounting.internal.bankrec.dto.AutoMatchResponse;
import com.positivity.accounting.internal.bankrec.dto.ReconciliationCandidatesResponse;
import com.positivity.accounting.internal.bankrec.dto.ReconciliationMatchCreateRequest;
import com.positivity.accounting.internal.bankrec.dto.ReconciliationMatchDecisionRequest;
import com.positivity.accounting.internal.bankrec.dto.ReconciliationMatchResponse;
import com.positivity.accounting.internal.bankrec.dto.ReconciliationUnmatchRequest;
import com.positivity.accounting.internal.bankrec.service.ReconciliationMatchingService;
import com.positivity.accounting.internal.security.AccountingPermissions;
import com.positivity.events.EmitEvent;
import com.positivity.shared.error.ApiError;
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
import org.jspecify.annotations.NonNull;
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
 * Matching in a bank reconciliation (SPEC-manual-bank-reconciliation §3.4, §4.6, §6.1; story S4, #2303):
 * ranked candidates, a propose-only auto-match, human matches (1:1, 1:N, N:1), accept / reject of proposals
 * and unmatch with a reason. {@code POST /matches} replaces F2's {@code /match}, and
 * {@code /matches/{matchId}/unmatch} its {@code /unmatch}. Reads need {@code accounting:reconciliation:view};
 * mutations {@code accounting:reconciliation:adjust}.
 */
@RestController
@RequestMapping("/v1/accounting/reconciliations")
@Tag(name = "Bank Reconciliation")
@RequiredArgsConstructor
@Validated
public class ReconciliationMatchController {

    private static final String RECONCILIATION_ID = "Reconciliation id";

    private final ReconciliationMatchingService matchingService;

    @GetMapping("/{reconciliationId}/candidates")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:reconciliation:view"})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.RECONCILIATION_VIEW + "')")
    @EmitEvent(id = "ACCOUNTING_RECONCILIATION_CANDIDATES", apiVersion = "1")
    @Operation(operationId = "listReconciliationCandidates", summary = "List Match Candidates", description = """
                    Ranks match candidates deterministically for one bank transaction (posted ledger lines on \
                    the account) or one ledger line (bank transactions), each with its score and reason codes: \
                    EXACT_AMOUNT +60, WITHIN_TOLERANCE +40, DATE_IN_WINDOW +20 × (1 − d/W), REFERENCE_MATCH +20, \
                    DESCRIPTION_SIMILAR up to +10; beyond W a widened window marks DATE_OUT_OF_WINDOW.
                    Use this tool to find the ledger lines a bank row should be matched to before \
                    createReconciliationMatch; use autoMatchReconciliation instead to propose matches for every \
                    unexplained bank row at once.
                    Preconditions: the reconciliation must exist; exactly one of bankTransactionId and glLineId.
                    Required inputs: reconciliationId as a path parameter and bankTransactionId or glLineId as a \
                    query parameter; windowDays optionally widens the date window W (default 7).
                    Emits an ACCOUNTING_RECONCILIATION_CANDIDATES event; no state changes.
                    Returns 400 VALIDATION_ERROR when neither or both subjects are named, and 404 \
                    RECONCILIATION_NOT_FOUND or BANK_TRANSACTION_NOT_FOUND when the reconciliation or the \
                    subject is unknown.
                    """)
    @ApiResponse(
            responseCode = "200",
            description = "Candidates ranked",
            content = @Content(schema = @Schema(implementation = ReconciliationCandidatesResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description = "Neither or both of bankTransactionId and glLineId (VALIDATION_ERROR)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Caller lacks accounting:reconciliation:view",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Reconciliation, bank transaction or ledger line not found (RECONCILIATION_NOT_FOUND /"
                    + " BANK_TRANSACTION_NOT_FOUND)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<ReconciliationCandidatesResponse> listCandidates(
            @Parameter(description = RECONCILIATION_ID, required = true) @PathVariable @NonNull UUID reconciliationId,
            @Parameter(description = "Bank transaction to find ledger candidates for") @RequestParam(required = false)
                    UUID bankTransactionId,
            @Parameter(description = "Ledger line to find bank candidates for") @RequestParam(required = false)
                    UUID glLineId,
            @Parameter(description = "Widen the date window to this many days", example = "30")
                    @RequestParam(required = false)
                    Integer windowDays) {
        return ResponseEntity.ok(matchingService.candidates(reconciliationId, bankTransactionId, glLineId, windowDays));
    }

    @PostMapping("/{reconciliationId}/auto-match")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:reconciliation:adjust"})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.RECONCILIATION_ADJUST + "')")
    @EmitEvent(id = "ACCOUNTING_RECONCILIATION_AUTO_MATCH", apiVersion = "1")
    @Operation(operationId = "autoMatchReconciliation", summary = "Propose Matches Automatically", description = """
                    Proposes a ONE_TO_ONE RULE match, state PROPOSED, for every unexplained bank transaction whose \
                    top candidate scores at least 90 and beats the second by at least 20; closer calls propose \
                    nothing and are counted as ambiguous. The system never accepts a match.
                    Use this tool to pre-pair the obvious rows before reviewing them with \
                    acceptReconciliationMatch or rejectReconciliationMatch; use createReconciliationMatch \
                    instead to record a pairing directly.
                    Preconditions: the reconciliation must be IN_PROGRESS (409 RECONCILIATION_ALREADY_FINALIZED when \
                    FINALIZED, RECONCILIATION_NOT_EDITABLE in any other status).
                    Required inputs: reconciliationId as a path parameter; the body is empty.
                    Emits an ACCOUNTING_RECONCILIATION_AUTO_MATCH event and writes a RECONCILIATION_AUTO_MATCH \
                    audit row with the counts.
                    Returns 404 RECONCILIATION_NOT_FOUND when the reconciliation is unknown and 409 \
                    RECONCILIATION_ALREADY_FINALIZED when it is finalized or RECONCILIATION_NOT_EDITABLE when it is \
                    not IN_PROGRESS.
                    """)
    @ApiResponse(
            responseCode = "200",
            description = "Proposals made",
            content = @Content(schema = @Schema(implementation = AutoMatchResponse.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Caller lacks accounting:reconciliation:adjust",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Reconciliation not found (RECONCILIATION_NOT_FOUND)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "Reconciliation already finalized (RECONCILIATION_ALREADY_FINALIZED) or not IN_PROGRESS"
                    + " (RECONCILIATION_NOT_EDITABLE), or a proposed line"
                    + " was concurrently matched (RECONCILIATION_LINE_INELIGIBLE)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<AutoMatchResponse> autoMatch(
            @Parameter(description = RECONCILIATION_ID, required = true) @PathVariable @NonNull UUID reconciliationId) {
        return ResponseEntity.ok(matchingService.autoMatch(reconciliationId));
    }

    @PostMapping("/{reconciliationId}/matches")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:reconciliation:adjust"})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.RECONCILIATION_ADJUST + "')")
    @EmitEvent(id = "ACCOUNTING_RECONCILIATION_MATCH", apiVersion = "1")
    @Operation(
            operationId = "createReconciliationMatch",
            summary = "Match Bank Transactions To Ledger Lines",
            description = """
                    Creates an ACCEPTED match of bank transactions to posted ledger lines on the reconciled \
                    account (1:1, 1:N or N:1), marks the bank rows MATCHED and clears any OPEN ledger-side \
                    outstanding item on the matched lines; the response serves toleranceUsed and the signed \
                    residual (bankTotal − ledgerTotal).
                    Use this tool to record that bank and ledger rows describe the same cash movement; use \
                    addReconciliationAdjustment instead for a bank-only item, and \
                    registerReconciliationOutstandingItem for a timing difference.
                    Preconditions: the reconciliation must be IN_PROGRESS (409 RECONCILIATION_ALREADY_FINALIZED when \
                    FINALIZED, RECONCILIATION_NOT_EDITABLE in any other status); bank rows UNMATCHED, settled, in no \
                    match or open item and dated on or before the window end; ledger lines POSTED, on the \
                    account, in no active match and dated on or before the window end; the sides must agree \
                    within 0.01. A justification of at least 10 characters is required for a non-1:1 match, any \
                    tolerance use, dates beyond the window, or a former possible duplicate.
                    Required inputs: reconciliationId as a path parameter; bankTransactionIds, glLineIds and \
                    requestId in the body.
                    Emits an ACCOUNTING_RECONCILIATION_MATCH event and a RECONCILIATION_MATCH audit row; no \
                    journal entry is posted.
                    Returns 201 with the match (200 with replayed true for a replayed requestId); 409 \
                    RECONCILIATION_LINE_INELIGIBLE for a row not matchable (including a ledger line dated after \
                    the window end), 409 RECONCILIATION_ALREADY_FINALIZED, RECONCILIATION_NOT_EDITABLE or \
                    IDEMPOTENCY_CONFLICT; 422 \
                    MATCH_AMOUNT_MISMATCH when the sides differ by more than 0.01, 422 \
                    MATCH_CARDINALITY_NOT_ALLOWED for N:M, and 422 MATCH_REQUIRES_REVIEW listing the reasons in \
                    fieldErrors[justification] when a justification is needed.
                    """)
    @ApiResponse(
            responseCode = "201",
            description = "Match created",
            content = @Content(schema = @Schema(implementation = ReconciliationMatchResponse.class)))
    @ApiResponse(
            responseCode = "200",
            description = "Replay of an earlier match with the same requestId (replayed = true)",
            content = @Content(schema = @Schema(implementation = ReconciliationMatchResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description = "Request body invalid (VALIDATION_ERROR) or justification shorter than 10 characters"
                    + " (JUSTIFICATION_REQUIRED)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Caller lacks accounting:reconciliation:adjust",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Reconciliation, bank transaction or ledger line not found",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "RECONCILIATION_LINE_INELIGIBLE, RECONCILIATION_ALREADY_FINALIZED,"
                    + " RECONCILIATION_NOT_EDITABLE or IDEMPOTENCY_CONFLICT",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "422",
            description = "MATCH_AMOUNT_MISMATCH, MATCH_CARDINALITY_NOT_ALLOWED or MATCH_REQUIRES_REVIEW",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<ReconciliationMatchResponse> createMatch(
            @Parameter(description = RECONCILIATION_ID, required = true) @PathVariable @NonNull UUID reconciliationId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "The members of the match and the command's requestId.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Batch deposit (1:N)", value = """
                                                                    {"bankTransactionIds":["019a0000-0000-7000-8000-000000000010"],
                                                                     "glLineIds":["019a0000-0000-7000-8000-000000000011",
                                                                                  "019a0000-0000-7000-8000-000000000012"],
                                                                     "justification":"Batch deposit of two receipts",
                                                                     "requestId":"019a0000-0000-7000-8000-000000000013"}
                                                                    """)))
                    @Valid
                    @RequestBody
                    @NonNull
                    ReconciliationMatchCreateRequest request) {
        ReconciliationMatchResponse response = matchingService.createMatch(reconciliationId, request);
        return ResponseEntity.status(response.isReplayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(response);
    }

    @PostMapping("/{reconciliationId}/matches/{matchId}/accept")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:reconciliation:adjust"})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.RECONCILIATION_ADJUST + "')")
    @EmitEvent(id = "ACCOUNTING_RECONCILIATION_MATCH_ACCEPT", apiVersion = "1")
    @Operation(operationId = "acceptReconciliationMatch", summary = "Accept Proposed Match", description = """
                    Accepts a PROPOSED match: the match becomes ACCEPTED, its bank rows MATCHED, and any OPEN \
                    ledger-side outstanding item on its lines CLEARED.
                    Use this tool to confirm a proposal from autoMatchReconciliation; use \
                    rejectReconciliationMatch instead to decline it.
                    Preconditions: the reconciliation must be IN_PROGRESS (409 RECONCILIATION_ALREADY_FINALIZED when \
                    FINALIZED, RECONCILIATION_NOT_EDITABLE in any other status); the match must be PROPOSED and its \
                    members still matchable; a justification is needed when the proposal uses the tolerance or \
                    spans dates beyond the window.
                    Required inputs: reconciliationId and matchId as path parameters; justification optional.
                    Emits an ACCOUNTING_RECONCILIATION_MATCH_ACCEPT event and a RECONCILIATION_MATCH_ACCEPT \
                    audit row.
                    Returns 404 RECONCILIATION_NOT_FOUND for an unknown match, 409 MATCH_STATE_INVALID when the \
                    match is not PROPOSED, 409 RECONCILIATION_LINE_INELIGIBLE when a member is no longer \
                    matchable, and 422 MATCH_REQUIRES_REVIEW when a justification is needed.
                    """)
    @ApiResponse(
            responseCode = "200",
            description = "Match accepted",
            content = @Content(schema = @Schema(implementation = ReconciliationMatchResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description = "Justification shorter than 10 characters (JUSTIFICATION_REQUIRED)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Caller lacks accounting:reconciliation:adjust",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Reconciliation or match not found (RECONCILIATION_NOT_FOUND)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "MATCH_STATE_INVALID, RECONCILIATION_LINE_INELIGIBLE, RECONCILIATION_ALREADY_FINALIZED or"
                    + " RECONCILIATION_NOT_EDITABLE",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "422",
            description = "A justification is needed (MATCH_REQUIRES_REVIEW)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<ReconciliationMatchResponse> acceptMatch(
            @Parameter(description = RECONCILIATION_ID, required = true) @PathVariable @NonNull UUID reconciliationId,
            @Parameter(description = "Match id", required = true) @PathVariable @NonNull UUID matchId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Optional justification (at least 10 characters when given).",
                            required = false,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(
                                                            name = "Accept with justification",
                                                            value =
                                                                    "{\"justification\":\"Amount within tolerance, same payer\"}")))
                    @Valid
                    @RequestBody(required = false)
                    ReconciliationMatchDecisionRequest request) {
        return ResponseEntity.ok(matchingService.accept(reconciliationId, matchId, orEmpty(request)));
    }

    @PostMapping("/{reconciliationId}/matches/{matchId}/reject")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:reconciliation:adjust"})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.RECONCILIATION_ADJUST + "')")
    @EmitEvent(id = "ACCOUNTING_RECONCILIATION_MATCH_REJECT", apiVersion = "1")
    @Operation(operationId = "rejectReconciliationMatch", summary = "Reject Proposed Match", description = """
                    Rejects a PROPOSED match: it becomes REJECTED (kept as history, never deleted) and its \
                    members are released for other matches.
                    Use this tool to decline a proposal from autoMatchReconciliation; use \
                    acceptReconciliationMatch instead to confirm it.
                    Preconditions: the reconciliation must be IN_PROGRESS (409 RECONCILIATION_ALREADY_FINALIZED when \
                    FINALIZED, RECONCILIATION_NOT_EDITABLE in any other status) and the match must be PROPOSED.
                    Required inputs: reconciliationId and matchId as path parameters; justification optional.
                    Emits an ACCOUNTING_RECONCILIATION_MATCH_REJECT event and a RECONCILIATION_MATCH_REJECT \
                    audit row.
                    Returns 404 RECONCILIATION_NOT_FOUND for an unknown match and 409 MATCH_STATE_INVALID when \
                    the match is not PROPOSED.
                    """)
    @ApiResponse(
            responseCode = "200",
            description = "Match rejected",
            content = @Content(schema = @Schema(implementation = ReconciliationMatchResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description = "Justification shorter than 10 characters (JUSTIFICATION_REQUIRED)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Caller lacks accounting:reconciliation:adjust",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Reconciliation or match not found (RECONCILIATION_NOT_FOUND)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "MATCH_STATE_INVALID, RECONCILIATION_ALREADY_FINALIZED or RECONCILIATION_NOT_EDITABLE",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<ReconciliationMatchResponse> rejectMatch(
            @Parameter(description = RECONCILIATION_ID, required = true) @PathVariable @NonNull UUID reconciliationId,
            @Parameter(description = "Match id", required = true) @PathVariable @NonNull UUID matchId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Optional justification (at least 10 characters when given).",
                            required = false,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(
                                                            name = "Reject with justification",
                                                            value =
                                                                    "{\"justification\":\"Different payer on the deposit slip\"}")))
                    @Valid
                    @RequestBody(required = false)
                    ReconciliationMatchDecisionRequest request) {
        return ResponseEntity.ok(matchingService.reject(reconciliationId, matchId, orEmpty(request)));
    }

    @PostMapping("/{reconciliationId}/matches/{matchId}/unmatch")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:reconciliation:adjust"})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.RECONCILIATION_ADJUST + "')")
    @EmitEvent(id = "ACCOUNTING_RECONCILIATION_UNMATCH", apiVersion = "1")
    @Operation(operationId = "unmatchReconciliationMatch", summary = "Unmatch Accepted Match", description = """
                    Undoes an ACCEPTED match: it becomes UNMATCHED with the reason (never deleted), its bank rows \
                    return to UNMATCHED, its lines are released for re-matching, and the outstanding items it had \
                    cleared re-open.
                    Use this tool to correct a wrong pairing; do not use rejectReconciliationMatch, which \
                    declines a proposal that was never accepted.
                    Preconditions: the reconciliation must be IN_PROGRESS (409 RECONCILIATION_ALREADY_FINALIZED when \
                    FINALIZED, RECONCILIATION_NOT_EDITABLE in any other status) and the match must be ACCEPTED.
                    Required inputs: reconciliationId and matchId as path parameters; reason (at least 10 \
                    characters) in the body.
                    Emits an ACCOUNTING_RECONCILIATION_UNMATCH event and a RECONCILIATION_UNMATCH audit row with \
                    the actor and reason.
                    Returns 400 VALIDATION_ERROR without a reason or JUSTIFICATION_REQUIRED for a short one, 404 \
                    RECONCILIATION_NOT_FOUND for an unknown match, and 409 MATCH_STATE_INVALID when the match is \
                    not ACCEPTED.
                    """)
    @ApiResponse(
            responseCode = "200",
            description = "Match unmatched",
            content = @Content(schema = @Schema(implementation = ReconciliationMatchResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description = "Reason missing (VALIDATION_ERROR) or shorter than 10 characters (JUSTIFICATION_REQUIRED)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Caller lacks accounting:reconciliation:adjust",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Reconciliation or match not found (RECONCILIATION_NOT_FOUND)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "MATCH_STATE_INVALID, RECONCILIATION_ALREADY_FINALIZED or RECONCILIATION_NOT_EDITABLE",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<ReconciliationMatchResponse> unmatchMatch(
            @Parameter(description = RECONCILIATION_ID, required = true) @PathVariable @NonNull UUID reconciliationId,
            @Parameter(description = "Match id", required = true) @PathVariable @NonNull UUID matchId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Why the match is undone.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(
                                                            name = "Wrong pairing",
                                                            value = "{\"reason\":\"Paired the wrong deposit\"}")))
                    @Valid
                    @RequestBody
                    @NonNull
                    ReconciliationUnmatchRequest request) {
        return ResponseEntity.ok(matchingService.unmatch(reconciliationId, matchId, request));
    }

    private static ReconciliationMatchDecisionRequest orEmpty(ReconciliationMatchDecisionRequest request) {
        return request != null ? request : new ReconciliationMatchDecisionRequest();
    }
}
