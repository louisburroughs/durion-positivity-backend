package com.positivity.accounting.internal.bankrec.controller;

import com.positivity.accounting.internal.bankrec.dto.AdjustmentReverseRequest;
import com.positivity.accounting.internal.bankrec.dto.BankReconciliationAdjustmentResponse;
import com.positivity.accounting.internal.bankrec.dto.ReconciliationAdjustmentRequest;
import com.positivity.accounting.internal.bankrec.service.ReconciliationAdjustmentService;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reconciliation adjustments (SPEC-manual-bank-reconciliation §3.5, §4.2, §4.6, §4.7, §4.9 path 2; story S4,
 * #2303): the only reconciliation artefact that posts a journal entry, and its explicit reversal. Posting needs
 * {@code accounting:reconciliation:adjust}, plus {@code accounting:reconciliation:approve} for an {@code OTHER}
 * above the tenant threshold (checked in the service) and {@code accounting:period:override} for a closed
 * period; reversing needs {@code accounting:reconciliation:approve}.
 */
@RestController
@RequestMapping("/v1/accounting/reconciliations")
@Tag(name = "Bank Reconciliation")
@RequiredArgsConstructor
@Validated
public class ReconciliationAdjustmentController {

    private static final String RECONCILIATION_ID = "Reconciliation id";

    private final ReconciliationAdjustmentService adjustmentService;

    @PostMapping("/{reconciliationId}/adjustments")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:reconciliation:adjust"})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.RECONCILIATION_ADJUST + "')")
    @EmitEvent(id = "ACCOUNTING_RECONCILIATION_ADJUSTMENT", apiVersion = "1")
    @Operation(
            operationId = "addReconciliationAdjustment",
            summary = "Post Reconciliation Adjustment",
            description = """
                    Posts a reconciliation adjustment as a real balanced journal entry through the \
                    accounting-period gate: positive debits the reconciled cash account against the type's mapped \
                    counter account, negative credits it. BANK_FEE and NSF_FEE are negative, INTEREST_EARNED \
                    positive; TRANSFER (either sign) posts against counterGlAccountId, another bank account, with \
                    no mapping. OTHER posts to the clearing account and names exactly one link: a bank transaction, \
                    settlesMatchId (a match residual; the server sets the amount to the served residual and \
                    replaces the match with an exact one) or bridgesStatementId (this statement's acknowledged gap; \
                    the server sets the amount to the opening difference). With a bankTransactionId the entry's \
                    cash line is matched to it as an ADJUSTMENT match. The entry is dated at the explaining date \
                    (the bank date, the residual match's latest bank date, or the day before the window) when its \
                    period is open, else at transactionDate.
                    Use this tool for a bank-only movement the books lack; do not use it for a timing difference \
                    (registerReconciliationOutstandingItem), a duplicate (bank-transaction duplicate review) or a \
                    books error (a journal-entry reversal).
                    Preconditions: the reconciliation must not be FINALIZED; an OTHER needs a justification of at \
                    least 10 characters and, above the tenant's BANK_REC_OTHER_APPROVAL_THRESHOLD (or while it is \
                    unset, for anything but a residual), accounting:reconciliation:approve; posting into a CLOSED \
                    period needs overrideJustification and accounting:period:override.
                    Required inputs: reconciliationId as a path parameter; type and requestId in the body, amount \
                    unless a residual or bridge is named, and the links and justification the type needs.
                    Emits an ACCOUNTING_RECONCILIATION_ADJUSTMENT event and a RECONCILIATION_ADJUSTMENT audit row, \
                    and posts a journal entry that changes GL balances.
                    Returns 201 with the adjustment (200 with replayed true for a replayed requestId); 400 \
                    JUSTIFICATION_REQUIRED; 403 RECONCILIATION_ADJUSTMENT_APPROVAL_REQUIRED; 409 \
                    RECONCILIATION_LINE_INELIGIBLE, ADJUSTMENT_BRIDGE_ALREADY_POSTED, IDEMPOTENCY_CONFLICT or \
                    RECONCILIATION_ALREADY_FINALIZED; 422 RECONCILIATION_ADJUSTMENT_SIGN_INVALID, \
                    ADJUSTMENT_LINK_REQUIRED, ADJUSTMENT_LINK_NOT_ELIGIBLE, GL_ACCOUNT_NOT_ACTIVE, \
                    ACCOUNT_NOT_RECONCILABLE, MATCH_AMOUNT_MISMATCH, PERIOD_CLOSED, PERIOD_HARD_LOCKED or \
                    GL_MAPPING_NOT_CONFIGURED when the rule named fails.
                    """)
    @ApiResponse(
            responseCode = "201",
            description = "Adjustment posted",
            content = @Content(schema = @Schema(implementation = BankReconciliationAdjustmentResponse.class)))
    @ApiResponse(
            responseCode = "200",
            description = "Replay of an earlier adjustment with the same requestId (replayed = true)",
            content = @Content(schema = @Schema(implementation = BankReconciliationAdjustmentResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description = "VALIDATION_ERROR or JUSTIFICATION_REQUIRED",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Caller lacks accounting:reconciliation:adjust, or approve for this OTHER"
                    + " (RECONCILIATION_ADJUSTMENT_APPROVAL_REQUIRED)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Reconciliation or bank transaction not found",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "RECONCILIATION_LINE_INELIGIBLE, ADJUSTMENT_BRIDGE_ALREADY_POSTED, IDEMPOTENCY_CONFLICT or"
                    + " RECONCILIATION_ALREADY_FINALIZED",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "422",
            description =
                    "RECONCILIATION_ADJUSTMENT_SIGN_INVALID, ADJUSTMENT_LINK_REQUIRED, ADJUSTMENT_LINK_NOT_ELIGIBLE,"
                            + " GL_ACCOUNT_NOT_ACTIVE, ACCOUNT_NOT_RECONCILABLE, MATCH_AMOUNT_MISMATCH, PERIOD_CLOSED,"
                            + " PERIOD_HARD_LOCKED or GL_MAPPING_NOT_CONFIGURED",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<BankReconciliationAdjustmentResponse> addReconciliationAdjustment(
            @Parameter(description = RECONCILIATION_ID, required = true) @PathVariable @NonNull UUID reconciliationId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "The typed adjustment, its links and the command's requestId.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(
                                                            name = "Bank fee linked to its bank row",
                                                            value = """
                                                                    {"type":"BANK_FEE",
                                                                     "amount":-15.00,
                                                                     "description":"Monthly account service fee",
                                                                     "bankTransactionId":"019a0000-0000-7000-8000-000000000030",
                                                                     "requestId":"019a0000-0000-7000-8000-000000000031"}
                                                                    """)))
                    @Valid
                    @RequestBody
                    @NonNull
                    ReconciliationAdjustmentRequest request) {
        BankReconciliationAdjustmentResponse response = adjustmentService.addAdjustment(reconciliationId, request);
        return ResponseEntity.status(response.isReplayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(response);
    }

    @PostMapping("/{reconciliationId}/adjustments/{adjustmentId}/reverse")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:reconciliation:approve"})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.RECONCILIATION_APPROVE + "')")
    @EmitEvent(id = "ACCOUNTING_RECONCILIATION_ADJUSTMENT_REVERSE", apiVersion = "1")
    @Operation(
            operationId = "reverseReconciliationAdjustment",
            summary = "Reverse Reconciliation Adjustment",
            description = """
                    Reverses a posted adjustment by posting the inverse journal entry through the period gate; the \
                    adjustment becomes REVERSED, its ADJUSTMENT match is unmatched and its bank transaction returns \
                    to UNMATCHED. The original and its reversal form a reversal pair that never counts as \
                    unexplained; a reversed gap bridge frees its statement for a new bridge.
                    Use this tool when an adjustment was wrong or the bank reversed the item; do not use the \
                    journal-entry reversal endpoint, which leaves the reconciliation's links in place.
                    Preconditions: the reconciliation must not be FINALIZED and the adjustment must be POSTED.
                    Required inputs: reconciliationId and adjustmentId as path parameters; reason (at least 10 \
                    characters) in the body; reversalDate and overrideJustification optional.
                    Emits an ACCOUNTING_RECONCILIATION_ADJUSTMENT_REVERSE event and a \
                    RECONCILIATION_ADJUSTMENT_REVERSE audit row, and posts the reversal journal entry.
                    Returns 400 VALIDATION_ERROR or JUSTIFICATION_REQUIRED for the reason, 404 \
                    RECONCILIATION_NOT_FOUND for an unknown adjustment, 409 ADJUSTMENT_ALREADY_REVERSED, \
                    JE_NOT_POSTED, JE_ALREADY_REVERSED or RECONCILIATION_ALREADY_FINALIZED, and 422 PERIOD_CLOSED \
                    or PERIOD_HARD_LOCKED when the reversal date is not open.
                    """)
    @ApiResponse(
            responseCode = "200",
            description = "Adjustment reversed",
            content = @Content(schema = @Schema(implementation = BankReconciliationAdjustmentResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description = "VALIDATION_ERROR or JUSTIFICATION_REQUIRED",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Caller lacks accounting:reconciliation:approve",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Reconciliation or adjustment not found (RECONCILIATION_NOT_FOUND)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "ADJUSTMENT_ALREADY_REVERSED, JE_NOT_POSTED, JE_ALREADY_REVERSED or"
                    + " RECONCILIATION_ALREADY_FINALIZED",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "422",
            description = "PERIOD_CLOSED or PERIOD_HARD_LOCKED",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<BankReconciliationAdjustmentResponse> reverseReconciliationAdjustment(
            @Parameter(description = RECONCILIATION_ID, required = true) @PathVariable @NonNull UUID reconciliationId,
            @Parameter(description = "Adjustment id", required = true) @PathVariable @NonNull UUID adjustmentId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Why the adjustment is reversed.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(
                                                            name = "Refunded fee",
                                                            value = "{\"reason\":\"Bank refunded the service fee\"}")))
                    @Valid
                    @RequestBody
                    @NonNull
                    AdjustmentReverseRequest request) {
        return ResponseEntity.ok(adjustmentService.reverse(reconciliationId, adjustmentId, request));
    }
}
