package com.positivity.warranty.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.warranty.internal.dto.CandidateLine;
import com.positivity.warranty.internal.dto.ClaimActionRequest;
import com.positivity.warranty.internal.dto.ClaimCreateRequest;
import com.positivity.warranty.internal.dto.ClaimDecisionRequest;
import com.positivity.warranty.internal.dto.ClaimLineRequest;
import com.positivity.warranty.internal.dto.ClaimNoteRequest;
import com.positivity.warranty.internal.dto.ClaimPhotoRequest;
import com.positivity.warranty.internal.dto.ClaimResponse;
import com.positivity.warranty.internal.dto.ClaimSummaryResponse;
import com.positivity.warranty.internal.dto.ClaimUpdateRequest;
import com.positivity.warranty.internal.enums.ClaimStatus;
import com.positivity.warranty.internal.security.WarrantyPermissions;
import com.positivity.warranty.internal.service.CandidateLineService;
import com.positivity.warranty.internal.service.ClaimService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Warranty-claim core lifecycle endpoints (PRD §7, §8): intake, search, editing while
 * DRAFT/INFO_NEEDED, submit, eligibility re-run, adjudication, cancel/close, and notes.
 * Thin controller — all rules live in {@code ClaimService}/{@code ClaimStateMachine}; illegal
 * lifecycle moves surface as 409 {@code ApiError} with {@code nextAction} listing legal moves.
 */
@Tag(name = "Warranty Claims", description = "Claim intake, adjudication, and lifecycle")
@RestController
@RequiredArgsConstructor
@Validated
@SecurityRequirement(name = "bearerAuth")
@RequestMapping("/v1/warranty/claims")
public class ClaimController {

    private final ClaimService claimService;
    private final CandidateLineService candidateLineService;

    // ------------------------------------------------------------------ intake / read

    @Operation(summary = "Create draft claim", description = "Creates a DRAFT claim; snapshots VIN/odometer")
    @ApiResponse(responseCode = "201", description = "Claim created.")
    @ApiResponse(responseCode = "400", description = "Invalid request.")
    @PreAuthorize("hasAuthority('" + WarrantyPermissions.CLAIM_CREATE + "')")
    @EmitEvent(id = "WARRANTY_CLAIM_CREATE", apiVersion = "1")
    @PostMapping
    public ResponseEntity<ClaimResponse> createClaim(@Valid @NotNull @RequestBody ClaimCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(claimService.create(request));
    }

    @Operation(
            summary = "Search claims",
            description = "Paged claim summaries; filter by customer, vehicle, status, claim code, location")
    @ApiResponse(responseCode = "200", description = "Claims returned.")
    @PreAuthorize("hasAuthority('" + WarrantyPermissions.CLAIM_VIEW + "')")
    @EmitEvent(id = "WARRANTY_CLAIM_SEARCH", apiVersion = "1")
    @GetMapping
    public ResponseEntity<Page<ClaimSummaryResponse>> searchClaims(
            @Parameter(description = "Filter by customer id") @RequestParam(required = false) UUID customerId,
            @Parameter(description = "Filter by vehicle id") @RequestParam(required = false) UUID vehicleId,
            @Parameter(description = "Filter by claim status") @RequestParam(required = false) ClaimStatus status,
            @Parameter(description = "Exact claim code, e.g. WC-2026-000123") @RequestParam(required = false)
                    String claimCode,
            @Parameter(description = "Filter by location id") @RequestParam(required = false) UUID locationId,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return ResponseEntity.ok(claimService.search(customerId, vehicleId, status, claimCode, locationId, pageable));
    }

    @Operation(
            summary = "Search candidate origin lines",
            description = "Cross-service search of invoices and workorders for origin-line matching (PRD §7 step 2)")
    @ApiResponse(responseCode = "200", description = "Candidate lines returned.")
    @PreAuthorize("hasAuthority('" + WarrantyPermissions.CLAIM_VIEW + "')")
    @EmitEvent(id = "WARRANTY_CANDIDATE_LINE_SEARCH", apiVersion = "1")
    @GetMapping("/candidate-lines")
    public ResponseEntity<List<CandidateLine>> searchCandidateLines(
            @Parameter(description = "Customer id", required = true) @RequestParam @NotNull UUID customerId,
            @Parameter(description = "Narrow by vehicle id") @RequestParam(required = false) UUID vehicleId,
            @Parameter(description = "Narrow by SKU") @RequestParam(required = false) String sku,
            @Parameter(description = "Narrow by catalog product id") @RequestParam(required = false)
                    UUID productEntityId) {
        return ResponseEntity.ok(candidateLineService.findCandidateLines(customerId, vehicleId, sku, productEntityId));
    }

    @Operation(
            summary = "Get claim",
            description = "Full detail incl. lines, settlements, reimbursements, part returns, history, notes")
    @ApiResponse(responseCode = "200", description = "Claim returned.")
    @ApiResponse(responseCode = "404", description = "Claim not found.")
    @PreAuthorize("hasAuthority('" + WarrantyPermissions.CLAIM_VIEW + "')")
    @GetMapping("/{id}")
    public ResponseEntity<ClaimResponse> getClaim(
            @Parameter(description = "Claim UUID", required = true) @PathVariable @NotNull UUID id) {
        return ResponseEntity.ok(claimService.getById(id));
    }

    // ------------------------------------------------------------------ edits (DRAFT / INFO_NEEDED)

    @Operation(summary = "Update claim", description = "Edit intake fields while DRAFT or INFO_NEEDED")
    @ApiResponse(responseCode = "200", description = "Claim updated.")
    @ApiResponse(responseCode = "404", description = "Claim not found.")
    @ApiResponse(responseCode = "409", description = "Claim is not editable in its current status.")
    @PreAuthorize("hasAuthority('" + WarrantyPermissions.CLAIM_CREATE + "')")
    @EmitEvent(id = "WARRANTY_CLAIM_UPDATE", apiVersion = "1")
    @PutMapping("/{id}")
    public ResponseEntity<ClaimResponse> updateClaim(
            @Parameter(description = "Claim UUID", required = true) @PathVariable @NotNull UUID id,
            @Valid @NotNull @RequestBody ClaimUpdateRequest request) {
        return ResponseEntity.ok(claimService.update(id, request));
    }

    @Operation(summary = "Add claim line", description = "Add a failed part/service line while DRAFT or INFO_NEEDED")
    @ApiResponse(responseCode = "200", description = "Line added.")
    @ApiResponse(responseCode = "404", description = "Claim not found.")
    @ApiResponse(responseCode = "409", description = "Claim is not editable in its current status.")
    @PreAuthorize("hasAuthority('" + WarrantyPermissions.CLAIM_CREATE + "')")
    @EmitEvent(id = "WARRANTY_CLAIM_UPDATE", apiVersion = "1")
    @PostMapping("/{id}/lines")
    public ResponseEntity<ClaimResponse> addLine(
            @Parameter(description = "Claim UUID", required = true) @PathVariable @NotNull UUID id,
            @Valid @NotNull @RequestBody ClaimLineRequest request) {
        return ResponseEntity.ok(claimService.addLine(id, request));
    }

    @Operation(summary = "Remove claim line", description = "Remove a claim line while DRAFT or INFO_NEEDED")
    @ApiResponse(responseCode = "200", description = "Line removed.")
    @ApiResponse(responseCode = "404", description = "Claim or line not found.")
    @ApiResponse(responseCode = "409", description = "Claim is not editable in its current status.")
    @PreAuthorize("hasAuthority('" + WarrantyPermissions.CLAIM_CREATE + "')")
    @EmitEvent(id = "WARRANTY_CLAIM_UPDATE", apiVersion = "1")
    @DeleteMapping("/{id}/lines/{lineId}")
    public ResponseEntity<ClaimResponse> removeLine(
            @Parameter(description = "Claim UUID", required = true) @PathVariable @NotNull UUID id,
            @Parameter(description = "Claim line UUID", required = true) @PathVariable @NotNull UUID lineId) {
        return ResponseEntity.ok(claimService.removeLine(id, lineId));
    }

    @Operation(summary = "Add photo", description = "Attach a photo-evidence URL while DRAFT or INFO_NEEDED")
    @ApiResponse(responseCode = "200", description = "Photo attached.")
    @ApiResponse(responseCode = "404", description = "Claim not found.")
    @ApiResponse(responseCode = "409", description = "Claim is not editable in its current status.")
    @PreAuthorize("hasAuthority('" + WarrantyPermissions.CLAIM_CREATE + "')")
    @EmitEvent(id = "WARRANTY_CLAIM_UPDATE", apiVersion = "1")
    @PostMapping("/{id}/photos")
    public ResponseEntity<ClaimResponse> addPhoto(
            @Parameter(description = "Claim UUID", required = true) @PathVariable @NotNull UUID id,
            @Valid @NotNull @RequestBody ClaimPhotoRequest request) {
        return ResponseEntity.ok(claimService.addPhoto(id, request.url()));
    }

    @Operation(summary = "Remove photo", description = "Detach a photo-evidence URL while DRAFT or INFO_NEEDED")
    @ApiResponse(responseCode = "200", description = "Photo detached.")
    @ApiResponse(responseCode = "404", description = "Claim or photo not found.")
    @ApiResponse(responseCode = "409", description = "Claim is not editable in its current status.")
    @PreAuthorize("hasAuthority('" + WarrantyPermissions.CLAIM_CREATE + "')")
    @EmitEvent(id = "WARRANTY_CLAIM_UPDATE", apiVersion = "1")
    @DeleteMapping("/{id}/photos")
    public ResponseEntity<ClaimResponse> removePhoto(
            @Parameter(description = "Claim UUID", required = true) @PathVariable @NotNull UUID id,
            @Parameter(description = "Photo URL to detach", required = true) @RequestParam @NotBlank String url) {
        return ResponseEntity.ok(claimService.removePhoto(id, url));
    }

    // ------------------------------------------------------------------ lifecycle

    @Operation(
            summary = "Submit claim",
            description = "Intake complete: runs eligibility, then DRAFT→SUBMITTED (or INFO_NEEDED→IN_REVIEW)")
    @ApiResponse(responseCode = "200", description = "Claim submitted.")
    @ApiResponse(responseCode = "400", description = "Intake incomplete (no lines, missing required photos).")
    @ApiResponse(responseCode = "404", description = "Claim not found.")
    @ApiResponse(responseCode = "409", description = "Illegal transition; nextAction lists legal moves.")
    @PreAuthorize("hasAuthority('" + WarrantyPermissions.CLAIM_SUBMIT + "')")
    @EmitEvent(id = "WARRANTY_CLAIM_SUBMIT", apiVersion = "1")
    @PostMapping("/{id}/submit")
    public ResponseEntity<ClaimResponse> submitClaim(
            @Parameter(description = "Claim UUID", required = true) @PathVariable @NotNull UUID id) {
        return ResponseEntity.ok(claimService.submit(id));
    }

    @Operation(summary = "Re-run eligibility", description = "Recompute the eligibility suggestion on demand")
    @ApiResponse(responseCode = "200", description = "Eligibility recomputed.")
    @ApiResponse(responseCode = "404", description = "Claim not found.")
    @ApiResponse(responseCode = "409", description = "Claim is terminal.")
    @PreAuthorize("hasAuthority('" + WarrantyPermissions.CLAIM_VIEW + "')")
    @EmitEvent(id = "WARRANTY_CLAIM_UPDATE", apiVersion = "1")
    @PostMapping("/{id}/eligibility")
    public ResponseEntity<ClaimResponse> evaluateEligibility(
            @Parameter(description = "Claim UUID", required = true) @PathVariable @NotNull UUID id) {
        return ResponseEntity.ok(claimService.evaluateEligibility(id));
    }

    @Operation(
            summary = "Decide claim",
            description = "APPROVE/DENY/REQUEST_INFO (implicitly begins review on a SUBMITTED claim);"
                    + " APPEAL reopens a DENIED claim. Deny, appeal, and suggestion overrides require a reason")
    @ApiResponse(responseCode = "200", description = "Decision applied.")
    @ApiResponse(responseCode = "400", description = "Missing required reason.")
    @ApiResponse(responseCode = "404", description = "Claim not found.")
    @ApiResponse(responseCode = "409", description = "Illegal transition; nextAction lists legal moves.")
    @PreAuthorize("hasAuthority('" + WarrantyPermissions.CLAIM_DECIDE + "')")
    @EmitEvent(id = "WARRANTY_CLAIM_DECIDE", apiVersion = "1")
    @PostMapping("/{id}/decision")
    public ResponseEntity<ClaimResponse> decideClaim(
            @Parameter(description = "Claim UUID", required = true) @PathVariable @NotNull UUID id,
            @Valid @NotNull @RequestBody ClaimDecisionRequest request) {
        return ResponseEntity.ok(claimService.decide(id, request));
    }

    @Operation(summary = "Cancel claim", description = "Cancel from DRAFT, SUBMITTED, or INFO_NEEDED")
    @ApiResponse(responseCode = "200", description = "Claim cancelled.")
    @ApiResponse(responseCode = "404", description = "Claim not found.")
    @ApiResponse(responseCode = "409", description = "Illegal transition; nextAction lists legal moves.")
    @PreAuthorize("hasAuthority('" + WarrantyPermissions.CLAIM_CANCEL + "')")
    @EmitEvent(id = "WARRANTY_CLAIM_CANCEL", apiVersion = "1")
    @PostMapping("/{id}/cancel")
    public ResponseEntity<ClaimResponse> cancelClaim(
            @Parameter(description = "Claim UUID", required = true) @PathVariable @NotNull UUID id,
            @Valid @RequestBody(required = false) ClaimActionRequest request) {
        return ResponseEntity.ok(claimService.cancel(id, request == null ? new ClaimActionRequest(null) : request));
    }

    @Operation(
            summary = "Close claim",
            description = "Close from SETTLED or DENIED once every reimbursement and part return is terminal")
    @ApiResponse(responseCode = "200", description = "Claim closed.")
    @ApiResponse(responseCode = "404", description = "Claim not found.")
    @ApiResponse(responseCode = "409", description = "Illegal transition or open child records; see nextAction.")
    @PreAuthorize("hasAuthority('" + WarrantyPermissions.CLAIM_CLOSE + "')")
    @EmitEvent(id = "WARRANTY_CLAIM_CLOSE", apiVersion = "1")
    @PostMapping("/{id}/close")
    public ResponseEntity<ClaimResponse> closeClaim(
            @Parameter(description = "Claim UUID", required = true) @PathVariable @NotNull UUID id,
            @Valid @RequestBody(required = false) ClaimActionRequest request) {
        return ResponseEntity.ok(claimService.close(id, request == null ? new ClaimActionRequest(null) : request));
    }

    @Operation(summary = "Add note", description = "Append a free-form staff note (any status)")
    @ApiResponse(responseCode = "201", description = "Note appended.")
    @ApiResponse(responseCode = "404", description = "Claim not found.")
    @PreAuthorize("hasAuthority('" + WarrantyPermissions.CLAIM_CREATE + "')")
    @EmitEvent(id = "WARRANTY_CLAIM_UPDATE", apiVersion = "1")
    @PostMapping("/{id}/notes")
    public ResponseEntity<ClaimResponse> addNote(
            @Parameter(description = "Claim UUID", required = true) @PathVariable @NotNull UUID id,
            @Valid @NotNull @RequestBody ClaimNoteRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(claimService.addNote(id, request));
    }
}
