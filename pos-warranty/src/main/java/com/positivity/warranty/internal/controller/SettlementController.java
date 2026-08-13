package com.positivity.warranty.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.warranty.internal.dto.SettlementCreateRequest;
import com.positivity.warranty.internal.dto.SettlementReconciliationRow;
import com.positivity.warranty.internal.dto.SettlementResponse;
import com.positivity.warranty.internal.security.WarrantyPermissions;
import com.positivity.warranty.internal.service.SettlementReconciliationService;
import com.positivity.warranty.internal.service.SettlementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Settlement execution endpoint (PRD §3.6, §8): the clerk executes the chosen settlement(s) on
 * an approved claim — warranty creates the invoice adjustment/refund via pos-invoice or links
 * the replacement workorder, and the first success moves the claim to {@code SETTLED}. Thin
 * controller — all rules live in {@code SettlementService}.
 */
@Tag(name = "Warranty Settlements", description = "Execute settlements on approved warranty claims")
@RestController
@RequiredArgsConstructor
@Validated
@SecurityRequirement(name = "bearerAuth")
@RequestMapping("/v1/warranty")
public class SettlementController {

    private final SettlementService settlementService;
    private final SettlementReconciliationService settlementReconciliationService;

    @Operation(operationId = "createSettlement", summary = "Execute a settlement", description = """
                    Executes one settlement on an APPROVED or already SETTLED claim: credit types \
                    (INVOICE_CREDIT, PRORATED_CREDIT, GOODWILL) create an invoice adjustment via pos-invoice, \
                    REFUND initiates a refund of a captured payment via pos-invoice, REPLACEMENT_WORKORDER links a \
                    workorder that staff already created through the normal flow, and NO_ACTION records that \
                    nothing is owed.
                    Use this tool when the clerk settles the customer; do not use decideClaim, which approves the \
                    claim first, and do not use submitReimbursement, which is the later vendor-recovery step that \
                    never blocks customer settlement.
                    Preconditions: the claim must be APPROVED or SETTLED (concurrent settlements of the same \
                    claim are serialized under a pessimistic lock), a replacement workorder must already exist in \
                    the event-fed workorder replica, and pos-invoice writes carry the settlement id as the \
                    idempotency key (externalReference).
                    Required inputs: claim id (UUID) and a body with settlementType, coveredAmount, and \
                    customerAmount (both zero or positive); replacementWorkorderId is required for \
                    REPLACEMENT_WORKORDER, invoiceId for the credit types, and invoiceId plus paymentId for \
                    REFUND.
                    Emits a WARRANTY_CLAIM_SETTLE event and enqueues warranty.claim.settled for pos-accounting; \
                    the first successful settlement moves the claim APPROVED to SETTLED, and a failed pos-invoice \
                    write keeps a durable FAILED settlement row for the reconciliation worklist.
                    Returns 400 when the type-specific reference id is missing, 404 when the claim does not \
                    exist, 409 when the claim is not settleable, 422 when the replacement workorder cannot be \
                    resolved, and 502 when the pos-invoice write fails (the settlement is recorded FAILED and \
                    staff retry with a fresh settlement after checking reconcileSettlements).
                    """)
    @ApiResponse(responseCode = "201", description = "Settlement executed.")
    @ApiResponse(responseCode = "404", description = "Claim not found.")
    @ApiResponse(responseCode = "409", description = "Claim is not in a settleable state.")
    @ApiResponse(responseCode = "422", description = "Referenced replacement workorder could not be resolved.")
    @ApiResponse(responseCode = "502", description = "pos-invoice write failed; settlement recorded as FAILED.")
    @PreAuthorize("hasAuthority('" + WarrantyPermissions.CLAIM_SETTLE + "')")
    @EmitEvent(id = "WARRANTY_CLAIM_SETTLE", apiVersion = "1")
    @PostMapping("/claims/{id}/settlements")
    public ResponseEntity<SettlementResponse> createSettlement(
            @Parameter(description = "Claim id") @PathVariable("id") UUID claimId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Settlement to execute: how the customer is made whole, the covered and"
                                    + " customer amounts, and the type-specific reference ids.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Prorated credit", value = """
                                                                    {"settlementType":"PRORATED_CREDIT",
                                                                     "coveredAmount":142.49,
                                                                     "customerAmount":47.50,
                                                                     "invoiceId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a04",
                                                                     "notes":"Prorated at 75% remaining tread"}
                                                                    """)))
                    @Valid
                    @NotNull
                    @RequestBody
                    SettlementCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(settlementService.create(claimId, request));
    }

    @Operation(
            operationId = "reconcileSettlements",
            summary = "Reconcile FAILED settlements against pos-invoice",
            description = """
                    Builds the reconciliation worklist for FAILED invoice-writing settlements by asking \
                    pos-invoice whether each adjustment or refund actually committed, matched by \
                    externalReference (the settlement id).
                    Use this tool to detect double-pay risk after a settlement failure; do not retry with \
                    createSettlement until the row shows committedOnInvoice=false — committedOnInvoice=true means \
                    pos-invoice committed the money write even though warranty recorded the settlement as FAILED.
                    Preconditions: none — only settlements already recorded FAILED with an invoice-writing type \
                    appear, and pos-invoice is consulted live at request time.
                    Required inputs: none; there are no parameters and no request body.
                    Emits a WARRANTY_SETTLEMENT_RECONCILIATION audit event; no state changes, this is a read-only \
                    cross-service check.
                    Returns 200 with one row per FAILED settlement, where checkStatus UNKNOWN means pos-invoice \
                    was unreachable for that invoice and the worklist should be re-run before acting.
                    """)
    @ApiResponse(responseCode = "200", description = "Reconciliation worklist.")
    @PreAuthorize("hasAuthority('" + WarrantyPermissions.CLAIM_SETTLE + "')")
    @EmitEvent(id = "WARRANTY_SETTLEMENT_RECONCILIATION", apiVersion = "1")
    @GetMapping("/settlements/reconciliation")
    public ResponseEntity<List<SettlementReconciliationRow>> reconcileSettlements() {
        return ResponseEntity.ok(settlementReconciliationService.reconcileFailedSettlements());
    }
}
