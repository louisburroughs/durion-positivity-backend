package com.positivity.warranty.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.warranty.internal.dto.ReimbursementResponse;
import com.positivity.warranty.internal.dto.ReimbursementSubmitRequest;
import com.positivity.warranty.internal.dto.ReimbursementUpdateRequest;
import com.positivity.warranty.internal.enums.ReimbursementStatus;
import com.positivity.warranty.internal.security.WarrantyPermissions;
import com.positivity.warranty.internal.service.ReimbursementService;
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
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Vendor-reimbursement child lifecycle endpoints (PRD §3.7, §8): back-office submission to the
 * provider, vendor decision / credit / write-off updates, and the open-credits worklist. Thin
 * controller — all rules live in {@code ReimbursementService}; illegal lifecycle moves surface
 * as 409 {@code ApiError} with {@code nextAction} listing the legal moves.
 */
@Tag(name = "Warranty Reimbursements", description = "Vendor reimbursement lifecycle and worklist")
@RestController
@RequiredArgsConstructor
@Validated
@SecurityRequirement(name = "bearerAuth")
@RequestMapping("/v1/warranty")
public class ReimbursementController {

    private final ReimbursementService reimbursementService;

    @Operation(operationId = "submitReimbursement", summary = "Submit vendor reimbursement", description = """
                    Submits the claim's single vendor reimbursement to the warranty provider, creating the record \
                    if needed and moving it NOT_SUBMITTED to SUBMITTED.
                    Use this tool for the back-office recovery step after the customer is settled; do not use \
                    updateReimbursement, which records the vendor's decision, credit receipt, or write-off on an \
                    already-submitted reimbursement.
                    Preconditions: the claim must be APPROVED or SETTLED (settle-customer-first), must have a \
                    provider assigned, the provider must not be DEALER-funded (self-funded programs are \
                    NOT_APPLICABLE), and the reimbursement must still be NOT_SUBMITTED — each claim has at most \
                    one.
                    Required inputs: claim id (UUID) as a path parameter and a body with amountRequested \
                    (positive decimal); vendorClaimReference and notes are optional.
                    Emits a WARRANTY_REIMBURSEMENT_SUBMIT event and enqueues warranty.reimbursement.submitted so \
                    pos-accounting can expect the vendor credit; the claim's own status is never touched.
                    Returns 404 when the claim or its provider cannot be resolved, and 409 when the claim state \
                    disallows submission, no provider is assigned, the provider is dealer-funded, or the \
                    reimbursement was already submitted (including a concurrent submit).
                    """)
    @ApiResponse(responseCode = "200", description = "Reimbursement submitted.")
    @ApiResponse(responseCode = "404", description = "Claim or provider not found.")
    @ApiResponse(responseCode = "409", description = "Claim state or reimbursement state does not allow submission.")
    @PreAuthorize("hasAuthority('" + WarrantyPermissions.REIMBURSEMENT_MANAGE + "')")
    @EmitEvent(id = "WARRANTY_REIMBURSEMENT_SUBMIT", apiVersion = "1")
    @PostMapping("/claims/{id}/reimbursement/submit")
    public ResponseEntity<ReimbursementResponse> submitReimbursement(
            @Parameter(description = "Claim id") @PathVariable("id") UUID claimId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Amount requested from the vendor with an optional vendor claim"
                                    + " reference and back-office notes.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Portal submission", value = """
                                                                    {"amountRequested":145.50,
                                                                     "vendorClaimReference":"MICH-CLM-99120",
                                                                     "notes":"Submitted via provider portal"}
                                                                    """)))
                    @Valid
                    @NotNull
                    @RequestBody
                    ReimbursementSubmitRequest request) {
        return ResponseEntity.ok(reimbursementService.submit(claimId, request));
    }

    @Operation(operationId = "updateReimbursement", summary = "Update vendor reimbursement", description = """
                    Records the vendor's resolution on a claim's reimbursement: a decision (APPROVED, \
                    PARTIALLY_APPROVED, DENIED), the credit receipt (CREDIT_RECEIVED), or a write-off \
                    (WRITTEN_OFF).
                    Use this tool after the vendor responds; do not use submitReimbursement, which performs the \
                    initial NOT_SUBMITTED-to-SUBMITTED move.
                    Preconditions: the claim must have a reimbursement record and the move must be legal in the \
                    child state machine — SUBMITTED may resolve to APPROVED, PARTIALLY_APPROVED, or DENIED, \
                    approvals may then move to CREDIT_RECEIVED, and any non-terminal state may be WRITTEN_OFF; \
                    DENIED, CREDIT_RECEIVED, WRITTEN_OFF, and NOT_APPLICABLE are terminal and unblock claim \
                    close.
                    Required inputs: claim id (UUID) as a path parameter and a body with status; amountApproved \
                    is required for APPROVED and PARTIALLY_APPROVED, and creditReference typically accompanies \
                    CREDIT_RECEIVED.
                    Emits a WARRANTY_REIMBURSEMENT_UPDATE event and enqueues warranty.reimbursement.resolved for \
                    every resolution status so pos-accounting can match or stop expecting the vendor credit.
                    Returns 400 when the target status is outside the updatable set or amountApproved is missing, \
                    404 when the claim or its reimbursement record does not exist, and 409 when the transition is \
                    illegal from the current status (nextAction lists the legal moves).
                    """)
    @ApiResponse(responseCode = "200", description = "Reimbursement updated.")
    @ApiResponse(
            responseCode = "400",
            description = "Target status outside the updatable set, or amountApproved missing when required.")
    @ApiResponse(responseCode = "404", description = "Claim or reimbursement not found.")
    @ApiResponse(responseCode = "409", description = "Illegal reimbursement transition.")
    @PreAuthorize("hasAuthority('" + WarrantyPermissions.REIMBURSEMENT_MANAGE + "')")
    @EmitEvent(id = "WARRANTY_REIMBURSEMENT_UPDATE", apiVersion = "1")
    @PutMapping("/claims/{id}/reimbursement")
    public ResponseEntity<ReimbursementResponse> updateReimbursement(
            @Parameter(description = "Claim id") @PathVariable("id") UUID claimId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Vendor resolution: target status with the approved amount, credit"
                                    + " reference, or notes that go with it.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Vendor approved", value = """
                                                                    {"status":"APPROVED",
                                                                     "amountApproved":130.00,
                                                                     "notes":"Vendor approved at adjusted labor rate"}
                                                                    """)))
                    @Valid
                    @NotNull
                    @RequestBody
                    ReimbursementUpdateRequest request) {
        return ResponseEntity.ok(reimbursementService.update(claimId, request));
    }

    @Operation(operationId = "listReimbursements", summary = "Reimbursement worklist", description = """
                    Returns the back-office worklist of vendor reimbursements across all claims, optionally \
                    filtered by status and provider.
                    Use this tool to work open vendor credits; use getClaim instead to see the reimbursement of \
                    one specific claim.
                    Preconditions: none — an empty list is returned when nothing matches.
                    Required inputs: status and providerId are optional query parameters and combine as AND when \
                    both are given; omitting both returns every reimbursement.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 200 with the list, which is empty rather than 404 when nothing matches.
                    """)
    @ApiResponse(responseCode = "200", description = "Reimbursements returned.")
    @PreAuthorize("hasAuthority('" + WarrantyPermissions.REIMBURSEMENT_VIEW + "')")
    @GetMapping("/reimbursements")
    public ResponseEntity<List<ReimbursementResponse>> listReimbursements(
            @Parameter(description = "Filter by reimbursement status") @RequestParam(required = false)
                    ReimbursementStatus status,
            @Parameter(description = "Filter by warranty provider id") @RequestParam(required = false)
                    UUID providerId) {
        return ResponseEntity.ok(reimbursementService.worklist(status, providerId));
    }
}
