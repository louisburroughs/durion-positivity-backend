package com.positivity.accounting.internal.bankrec.controller;

import com.positivity.accounting.internal.bankrec.dto.OutstandingItemJustificationRequest;
import com.positivity.accounting.internal.bankrec.dto.OutstandingItemReasonRequest;
import com.positivity.accounting.internal.bankrec.dto.OutstandingItemRegisterRequest;
import com.positivity.accounting.internal.bankrec.dto.OutstandingItemResponse;
import com.positivity.accounting.internal.bankrec.service.ReconciliationOutstandingItemService;
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
 * Outstanding (timing) items of a bank reconciliation (SPEC-manual-bank-reconciliation §3.6, §6.1; story S4,
 * #2303): register, release, reaffirm an aged {@code OTHER_LEDGER_TIMING} item ({@code adjust}) and clear an
 * item in an acknowledged gap ({@code accounting:reconciliation:approve}). Nothing here posts a journal entry.
 */
@RestController
@RequestMapping("/v1/accounting/reconciliations")
@Tag(name = "Bank Reconciliation")
@RequiredArgsConstructor
@Validated
public class ReconciliationOutstandingItemController {

    private static final String RECONCILIATION_ID = "Reconciliation id";
    private static final String ITEM_ID = "Outstanding item id";

    private final ReconciliationOutstandingItemService itemService;

    @PostMapping("/{reconciliationId}/outstanding-items")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:reconciliation:adjust"})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.RECONCILIATION_ADJUST + "')")
    @EmitEvent(id = "ACCOUNTING_RECONCILIATION_OUTSTANDING_REGISTER", apiVersion = "1")
    @Operation(
            operationId = "registerReconciliationOutstandingItem",
            summary = "Register Outstanding Item",
            description = """
                    Registers a non-posting outstanding item: a deposit in transit (a debit to cash), an \
                    outstanding check (a credit) or another ledger timing item on a posted ledger line, or a bank \
                    error the bank will correct on a bank transaction. The item enters the explicit equation's \
                    bank side while it is open and carries forward until matched, cleared or released.
                    Use this tool for a timing difference; do not use addReconciliationAdjustment, which posts a \
                    journal entry, and use createReconciliationMatch when the other side is already present.
                    Preconditions: the reconciliation must not be FINALIZED; the line or bank transaction must be \
                    on the account, dated on or before the window end, in no active match and in no OPEN item; a \
                    deposit in transit must be positive and an outstanding check negative.
                    Required inputs: reconciliationId as a path parameter; itemKind and exactly one of glLineId \
                    or bankTransactionId in the body; justification (at least 10 characters) for \
                    OTHER_LEDGER_TIMING, BANK_ERROR_PENDING and an item older than the aging days.
                    Emits an ACCOUNTING_RECONCILIATION_OUTSTANDING_REGISTER event and a \
                    RECONCILIATION_OUTSTANDING_REGISTER audit row; no journal entry is posted.
                    Returns 400 JUSTIFICATION_REQUIRED when a needed justification is missing or short, 409 \
                    RECONCILIATION_ALREADY_FINALIZED or RECONCILIATION_LINE_INELIGIBLE (a concurrent \
                    registration), and 422 OUTSTANDING_ITEM_NOT_ELIGIBLE when the line, sign, window or state \
                    does not allow the item.
                    """)
    @ApiResponse(
            responseCode = "201",
            description = "Item registered",
            content = @Content(schema = @Schema(implementation = OutstandingItemResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description = "VALIDATION_ERROR or JUSTIFICATION_REQUIRED",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Caller lacks accounting:reconciliation:adjust",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Reconciliation, line or bank transaction not found",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "RECONCILIATION_ALREADY_FINALIZED or RECONCILIATION_LINE_INELIGIBLE",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "422",
            description = "OUTSTANDING_ITEM_NOT_ELIGIBLE",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<OutstandingItemResponse> registerItem(
            @Parameter(description = RECONCILIATION_ID, required = true) @PathVariable @NonNull UUID reconciliationId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "The line or bank transaction and the item kind.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Deposit in transit", value = """
                                                                    {"glLineId":"019a0000-0000-7000-8000-000000000020",
                                                                     "itemKind":"DEPOSIT_IN_TRANSIT"}
                                                                    """)))
                    @Valid
                    @RequestBody
                    @NonNull
                    OutstandingItemRegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(itemService.register(reconciliationId, request));
    }

    @PostMapping("/{reconciliationId}/outstanding-items/{itemId}/release")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:reconciliation:adjust"})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.RECONCILIATION_ADJUST + "')")
    @EmitEvent(id = "ACCOUNTING_RECONCILIATION_OUTSTANDING_RELEASE", apiVersion = "1")
    @Operation(
            operationId = "releaseReconciliationOutstandingItem",
            summary = "Release Outstanding Item",
            description = """
                    Releases an OPEN outstanding item with a reason: it leaves the equation and its line or bank \
                    transaction is free again.
                    Use this tool to undo a wrong registration; do not use it for an item whose other side has \
                    appeared, which createReconciliationMatch clears.
                    Preconditions: the reconciliation must not be FINALIZED; the item must be OPEN on the account \
                    and its registering reconciliation not FINALIZED.
                    Required inputs: reconciliationId and itemId as path parameters; reason (at least 10 \
                    characters) in the body.
                    Emits an ACCOUNTING_RECONCILIATION_OUTSTANDING_RELEASE event and a \
                    RECONCILIATION_OUTSTANDING_RELEASE audit row.
                    Returns 400 VALIDATION_ERROR or JUSTIFICATION_REQUIRED for a missing or short reason, 404 \
                    RECONCILIATION_NOT_FOUND for an unknown item, and 422 OUTSTANDING_ITEM_NOT_ELIGIBLE when the \
                    item is not OPEN or its registering reconciliation is FINALIZED.
                    """)
    @ApiResponse(
            responseCode = "200",
            description = "Item released",
            content = @Content(schema = @Schema(implementation = OutstandingItemResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description = "VALIDATION_ERROR or JUSTIFICATION_REQUIRED",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Caller lacks accounting:reconciliation:adjust",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Reconciliation or item not found (RECONCILIATION_NOT_FOUND)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "RECONCILIATION_ALREADY_FINALIZED",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "422",
            description = "OUTSTANDING_ITEM_NOT_ELIGIBLE",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<OutstandingItemResponse> releaseItem(
            @Parameter(description = RECONCILIATION_ID, required = true) @PathVariable @NonNull UUID reconciliationId,
            @Parameter(description = ITEM_ID, required = true) @PathVariable @NonNull UUID itemId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Why the item is released.",
                            required = true,
                            content = @Content(mediaType = "application/json"))
                    @Valid
                    @RequestBody
                    @NonNull
                    OutstandingItemReasonRequest request) {
        return ResponseEntity.ok(itemService.release(reconciliationId, itemId, request));
    }

    @PostMapping("/{reconciliationId}/outstanding-items/{itemId}/reaffirm")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:reconciliation:adjust"})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.RECONCILIATION_ADJUST + "')")
    @EmitEvent(id = "ACCOUNTING_RECONCILIATION_OUTSTANDING_REAFFIRM", apiVersion = "1")
    @Operation(
            operationId = "reaffirmReconciliationOutstandingItem",
            summary = "Reaffirm Aged Timing Item",
            description = """
                    Reaffirms an aged OTHER_LEDGER_TIMING item in this reconciliation: an item dated more than the \
                    aging days before the window end counts as an unexplained ledger line until the preparer \
                    re-judges it here, and counts again in the next window.
                    Use this tool when the timing explanation still holds; release the item instead, or correct \
                    the books by a journal-entry reversal, when it does not.
                    Preconditions: the reconciliation must not be FINALIZED; the item must be an OPEN \
                    OTHER_LEDGER_TIMING item on the account, aged at this window's end.
                    Required inputs: reconciliationId and itemId as path parameters; justification (at least 10 \
                    characters) in the body.
                    Emits an ACCOUNTING_RECONCILIATION_OUTSTANDING_REAFFIRM event and a \
                    RECONCILIATION_OUTSTANDING_REAFFIRM audit row.
                    Returns 400 JUSTIFICATION_REQUIRED for a missing or short justification, 404 \
                    RECONCILIATION_NOT_FOUND for an unknown item, and 422 OUTSTANDING_ITEM_NOT_ELIGIBLE when the \
                    item is not an aged OPEN OTHER_LEDGER_TIMING item.
                    """)
    @ApiResponse(
            responseCode = "200",
            description = "Item reaffirmed",
            content = @Content(schema = @Schema(implementation = OutstandingItemResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description = "JUSTIFICATION_REQUIRED",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Caller lacks accounting:reconciliation:adjust",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Reconciliation or item not found (RECONCILIATION_NOT_FOUND)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "RECONCILIATION_ALREADY_FINALIZED",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "422",
            description = "OUTSTANDING_ITEM_NOT_ELIGIBLE",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<OutstandingItemResponse> reaffirmItem(
            @Parameter(description = RECONCILIATION_ID, required = true) @PathVariable @NonNull UUID reconciliationId,
            @Parameter(description = ITEM_ID, required = true) @PathVariable @NonNull UUID itemId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Why the timing explanation still holds.",
                            required = true,
                            content = @Content(mediaType = "application/json"))
                    @Valid
                    @RequestBody
                    @NonNull
                    OutstandingItemJustificationRequest request) {
        return ResponseEntity.ok(itemService.reaffirm(reconciliationId, itemId, request));
    }

    @PostMapping("/{reconciliationId}/outstanding-items/{itemId}/clear-in-gap")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:reconciliation:approve"})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.RECONCILIATION_APPROVE + "')")
    @EmitEvent(id = "ACCOUNTING_RECONCILIATION_OUTSTANDING_CLEAR_IN_GAP", apiVersion = "1")
    @Operation(
            operationId = "clearReconciliationOutstandingItemInGap",
            summary = "Clear Outstanding Item In Gap",
            description = """
                    Closes an OPEN item registered in an earlier reconciliation whose other side appeared during \
                    the gap this reconciliation's statement acknowledges: the item becomes CLEARED_IN_GAP with \
                    closedOn the day before the statement start and leaves this window's opening and closing \
                    terms.
                    Use this tool only in the reconciliation of an acknowledged statement; use \
                    createReconciliationMatch for an item whose other side is in a bank row, and \
                    releaseReconciliationOutstandingItem to undo a wrong registration.
                    Preconditions: the reconciliation must not be FINALIZED and rest on a statement with a gap \
                    acknowledgement; the item must be OPEN, dated before the statement start and registered in \
                    an earlier reconciliation.
                    Required inputs: reconciliationId and itemId as path parameters; justification (at least 10 \
                    characters) in the body.
                    Emits an ACCOUNTING_RECONCILIATION_OUTSTANDING_CLEAR_IN_GAP event and a \
                    RECONCILIATION_OUTSTANDING_CLEAR_IN_GAP audit row.
                    Returns 400 JUSTIFICATION_REQUIRED, 403 without accounting:reconciliation:approve, 404 \
                    RECONCILIATION_NOT_FOUND for an unknown item, and 422 OUTSTANDING_ITEM_NOT_ELIGIBLE when the \
                    statement has no acknowledgement or the item fails its rule.
                    """)
    @ApiResponse(
            responseCode = "200",
            description = "Item cleared in the gap",
            content = @Content(schema = @Schema(implementation = OutstandingItemResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description = "JUSTIFICATION_REQUIRED",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Caller lacks accounting:reconciliation:approve",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Reconciliation or item not found (RECONCILIATION_NOT_FOUND)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "RECONCILIATION_ALREADY_FINALIZED",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "422",
            description = "OUTSTANDING_ITEM_NOT_ELIGIBLE",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<OutstandingItemResponse> clearItemInGap(
            @Parameter(description = RECONCILIATION_ID, required = true) @PathVariable @NonNull UUID reconciliationId,
            @Parameter(description = ITEM_ID, required = true) @PathVariable @NonNull UUID itemId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Why the other side is taken to have cleared during the gap.",
                            required = true,
                            content = @Content(mediaType = "application/json"))
                    @Valid
                    @RequestBody
                    @NonNull
                    OutstandingItemJustificationRequest request) {
        return ResponseEntity.ok(itemService.clearInGap(reconciliationId, itemId, request));
    }
}
