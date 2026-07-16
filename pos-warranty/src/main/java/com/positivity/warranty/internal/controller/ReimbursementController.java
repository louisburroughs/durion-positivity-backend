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

    @Operation(
            summary = "Submit vendor reimbursement",
            description = "Creates/updates the claim's single reimbursement NOT_SUBMITTED -> SUBMITTED; claim must"
                    + " be APPROVED or SETTLED and provider must not be dealer-funded")
    @ApiResponse(responseCode = "200", description = "Reimbursement submitted.")
    @ApiResponse(responseCode = "404", description = "Claim or provider not found.")
    @ApiResponse(responseCode = "409", description = "Claim state or reimbursement state does not allow submission.")
    @PreAuthorize("hasAuthority('" + WarrantyPermissions.REIMBURSEMENT_MANAGE + "')")
    @EmitEvent(id = "WARRANTY_REIMBURSEMENT_SUBMIT", apiVersion = "1")
    @PostMapping("/claims/{id}/reimbursement/submit")
    public ResponseEntity<ReimbursementResponse> submitReimbursement(
            @Parameter(description = "Claim id") @PathVariable("id") UUID claimId,
            @Valid @NotNull @RequestBody ReimbursementSubmitRequest request) {
        return ResponseEntity.ok(reimbursementService.submit(claimId, request));
    }

    @Operation(
            summary = "Update vendor reimbursement",
            description = "Records a vendor decision (APPROVED/PARTIALLY_APPROVED/DENIED), credit receipt, or"
                    + " write-off; only legal child-lifecycle transitions are accepted")
    @ApiResponse(responseCode = "200", description = "Reimbursement updated.")
    @ApiResponse(responseCode = "404", description = "Claim or reimbursement not found.")
    @ApiResponse(responseCode = "409", description = "Illegal reimbursement transition.")
    @PreAuthorize("hasAuthority('" + WarrantyPermissions.REIMBURSEMENT_MANAGE + "')")
    @EmitEvent(id = "WARRANTY_REIMBURSEMENT_UPDATE", apiVersion = "1")
    @PutMapping("/claims/{id}/reimbursement")
    public ResponseEntity<ReimbursementResponse> updateReimbursement(
            @Parameter(description = "Claim id") @PathVariable("id") UUID claimId,
            @Valid @NotNull @RequestBody ReimbursementUpdateRequest request) {
        return ResponseEntity.ok(reimbursementService.update(claimId, request));
    }

    @Operation(
            summary = "Reimbursement worklist",
            description = "Back-office worklist of vendor reimbursements, filterable by status and provider")
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
