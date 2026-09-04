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
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
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

    private static final String CLAIM_CREATE_EXAMPLE = """
            {"claimType":"ROAD_HAZARD",
             "customerId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a01",
             "vehicleId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a02",
             "locationId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a03",
             "originInvoiceId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a04",
             "originSaleDate":"2025-11-02",
             "failureDescription":"Sidewall puncture, right rear tire",
             "failureDate":"2026-08-10",
             "photoEvidenceUrls":["https://media.example.com/claims/tire-1.jpg"],
             "lines":[{"sourceType":"INVOICE_LINE",
                       "sourceId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a04",
                       "sourceLineId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a05",
                       "productEntityId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a06",
                       "sku":"MICH-DEF2-225-65R17",
                       "description":"Michelin Defender2 225/65R17",
                       "quantity":1,
                       "originalUnitPrice":189.99,
                       "originalTreadDepth":12,
                       "measuredTreadDepth":9}]}
            """;

    private static final String CLAIM_UPDATE_EXAMPLE = """
            {"claimType":"ROAD_HAZARD",
             "locationId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a03",
             "originInvoiceId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a04",
             "originSaleDate":"2025-11-02",
             "failureDescription":"Sidewall puncture, right rear tire — updated after inspection",
             "failureDate":"2026-08-09"}
            """;

    private static final String CLAIM_LINE_EXAMPLE = """
            {"sourceType":"MANUAL",
             "sku":"MICH-DEF2-225-65R17",
             "description":"Michelin Defender2 225/65R17",
             "serialNumber":"SN-4471002",
             "dotNumber":"DOT Y9RJ FPUU 2325",
             "quantity":1,
             "originalUnitPrice":189.99,
             "originalTreadDepth":12,
             "measuredTreadDepth":9}
            """;

    private static final String CLAIM_DECISION_EXAMPLE = """
            {"decision":"APPROVE",
             "reason":"Covered failure verified against policy terms",
             "lineDecisions":[{"lineId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a07",
                               "amountApproved":142.49,
                               "lineDisposition":"PARTIALLY_APPROVED",
                               "overrideReason":"Prorated to 75% remaining tread per manual gauge reading"}]}
            """;

    private final ClaimService claimService;
    private final CandidateLineService candidateLineService;

    // ------------------------------------------------------------------ intake / read

    @Operation(operationId = "createClaim", summary = "Create draft claim", description = """
                    Creates a DRAFT warranty claim for a customer and vehicle, assigning the next WC-{yyyy}-{seq} \
                    claim code and freezing the VIN and odometer snapshot from the event-fed vehicle replica when \
                    the vehicle fact has already arrived; walk-ins with no locatable sale leave the origin fields \
                    null and the claim is flagged originUnverified.
                    Use this tool to open a brand-new claim at intake; do not use updateClaim, which edits an \
                    existing DRAFT or INFO_NEEDED claim, and use searchCandidateLines first to locate the origin \
                    sale lines.
                    Preconditions: none beyond a known customerId and vehicleId — the vehicle replica may still be \
                    empty (the claim is created with a null snapshot) and no origin sale reference is required.
                    Required inputs: claimType (MANUFACTURER_DEFECT, DEALER_WORKMANSHIP, ROAD_HAZARD, or \
                    EXTENDED_PLAN), customerId (UUID), and vehicleId (UUID); locationId, origin \
                    workorder/invoice ids, originSaleDate, registrationId, failure details, photoEvidenceUrls, and \
                    initial lines are optional, and each line's quantity defaults to 1.
                    Emits a WARRANTY_CLAIM_CREATE event, records a DRAFT status-history row, and publishes the \
                    claim snapshot.
                    Returns 201 with the DRAFT claim; there is no duplicate check, so repeating the call creates a \
                    second claim.
                    """)
    @ApiResponse(responseCode = "201", description = "Claim created.")
    @ApiResponse(responseCode = "400", description = "Invalid request.")
    @PreAuthorize("hasAuthority('" + WarrantyPermissions.CLAIM_CREATE + "')")
    @EmitEvent(id = "WARRANTY_CLAIM_CREATE", apiVersion = "1")
    @PostMapping
    public ResponseEntity<ClaimResponse> createClaim(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Draft-claim intake payload: customer, vehicle, claim type, and optional"
                                    + " origin references, failure details, photos, and initial lines.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(
                                                            name = "Road-hazard tire claim",
                                                            value = CLAIM_CREATE_EXAMPLE)))
                    @Valid
                    @NotNull
                    @RequestBody
                    ClaimCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(claimService.create(request));
    }

    @Operation(operationId = "searchClaims", summary = "Search claims", description = """
                    Searches warranty claims and returns a page of claim summaries filtered by customer, vehicle, \
                    status, claim code, and location.
                    Use this tool to locate claims by criteria or to browse a worklist; do not use getClaim, which \
                    requires a known claim id and returns the full detail including lines, settlements, and history.
                    Preconditions: none — an empty page is returned when nothing matches.
                    Required inputs: every filter is optional; claimCode must be exact (for example WC-2026-000123) \
                    and short-circuits the other filters to at most one match, and paging defaults to size 20 \
                    sorted by createdAt descending.
                    Emits a WARRANTY_CLAIM_SEARCH audit event; no claim state changes, this is a read-only \
                    projection.
                    Returns 200 with the page, which is empty rather than 404 when no claim matches.
                    """)
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
            @ParameterObject @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
                    Pageable pageable) {
        return ResponseEntity.ok(claimService.search(customerId, vehicleId, status, claimCode, locationId, pageable));
    }

    @Operation(operationId = "searchCandidateLines", summary = "Search candidate origin lines", description = """
                    Searches the event-fed invoice and workorder replicas for a customer's historical sale lines \
                    that could be the origin of a warranty claim (PRD §7 step 2).
                    Use this tool during intake to match a failed part to its original sale before creating claim \
                    lines; do not use searchClaims, which searches existing warranty claims rather than origin \
                    sales.
                    Preconditions: the replicas are eventually consistent and each source degrades independently — \
                    a read failure on one source still returns the other's results, and a very recent sale may not \
                    have arrived yet.
                    Required inputs: customerId (UUID) is required; vehicleId, sku, and productEntityId optionally \
                    narrow the match, workorder parts match by productEntityId directly while invoice and service \
                    lines match by description tokens, and fan-out is capped at 25 invoices and 25 workorders.
                    Emits a WARRANTY_CANDIDATE_LINE_SEARCH audit event; no state changes, this is a read-only \
                    cross-replica projection.
                    Returns 200 with candidate lines, which may be empty or partial — the clerk falls back to \
                    manual origin entry (originUnverified) when the sale cannot be located.
                    """)
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

    @Operation(operationId = "getClaim", summary = "Get claim", description = """
                    Returns the full warranty claim detail, including lines, settlements, reimbursements, part \
                    returns, status history, and notes.
                    Use this tool when the claim id is already known; use searchClaims instead to locate a claim \
                    by customer, vehicle, status, or claim code.
                    Preconditions: the claim must exist.
                    Required inputs: id (UUID) as a path parameter; there is no request body and no filtering.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 404 when no claim exists for the supplied id.
                    """)
    @ApiResponse(responseCode = "200", description = "Claim returned.")
    @ApiResponse(responseCode = "404", description = "Claim not found.")
    @PreAuthorize("hasAuthority('" + WarrantyPermissions.CLAIM_VIEW + "')")
    @GetMapping("/{id}")
    public ResponseEntity<ClaimResponse> getClaim(
            @Parameter(description = "Claim UUID", required = true) @PathVariable @NotNull UUID id) {
        return ResponseEntity.ok(claimService.getById(id));
    }

    // ------------------------------------------------------------------ edits (DRAFT / INFO_NEEDED)

    @Operation(operationId = "updateClaim", summary = "Update claim", description = """
                    Replaces the intake fields of a claim that is still DRAFT or INFO_NEEDED: origin references, \
                    sale date, registration, failure details, and location use full-update semantics where null \
                    clears the value, while claimType and photoEvidenceUrls change only when non-null.
                    Use this tool to correct intake data before adjudication; do not use decideClaim, which \
                    records the approve/deny decision, and manage lines through addClaimLine and removeClaimLine \
                    rather than this operation.
                    Preconditions: the claim must exist and be in DRAFT or INFO_NEEDED — every other status \
                    rejects edits.
                    Required inputs: id (UUID) as a path parameter and the update body; clearing both \
                    originWorkorderId and originInvoiceId re-flags the claim originUnverified.
                    Emits a WARRANTY_CLAIM_UPDATE event and republishes the claim snapshot.
                    Returns 404 when the claim does not exist, and 409 (WARRANTY_CLAIM_NOT_EDITABLE) when the \
                    claim is not DRAFT or INFO_NEEDED.
                    """)
    @ApiResponse(responseCode = "200", description = "Claim updated.")
    @ApiResponse(responseCode = "404", description = "Claim not found.")
    @ApiResponse(responseCode = "409", description = "Claim is not editable in its current status.")
    @PreAuthorize("hasAuthority('" + WarrantyPermissions.CLAIM_CREATE + "')")
    @EmitEvent(id = "WARRANTY_CLAIM_UPDATE", apiVersion = "1")
    @PutMapping("/{id}")
    public ResponseEntity<ClaimResponse> updateClaim(
            @Parameter(description = "Claim UUID", required = true) @PathVariable @NotNull UUID id,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Intake fields to replace on the claim; null clears origin, failure, and"
                                    + " location fields, while claimType and photoEvidenceUrls apply only when"
                                    + " non-null.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(
                                                            name = "Correct intake data",
                                                            value = CLAIM_UPDATE_EXAMPLE)))
                    @Valid
                    @NotNull
                    @RequestBody
                    ClaimUpdateRequest request) {
        return ResponseEntity.ok(claimService.update(id, request));
    }

    @Operation(operationId = "addClaimLine", summary = "Add claim line", description = """
                    Adds one failed part or service line to a claim that is still DRAFT or INFO_NEEDED, \
                    snapshotting SKU, description, serial numbers, price, and tread depths at intake.
                    Use this tool to build the claim's line list; do not use updateClaim, which edits header \
                    fields and cannot touch lines, and use removeClaimLine to take a line off.
                    Preconditions: the claim must exist and be in DRAFT or INFO_NEEDED.
                    Required inputs: id (UUID) as a path parameter and a line body with sourceType (INVOICE_LINE, \
                    WORKORDER_PART, WORKORDER_SERVICE, or MANUAL); sourceId and sourceLineId are null for MANUAL \
                    lines, quantity defaults to 1, and tread depths are integers in 32nds of an inch.
                    Emits a WARRANTY_CLAIM_UPDATE event and republishes the claim snapshot.
                    Returns 404 when the claim does not exist, and 409 when the claim is not editable in its \
                    current status.
                    """)
    @ApiResponse(responseCode = "200", description = "Line added.")
    @ApiResponse(responseCode = "404", description = "Claim not found.")
    @ApiResponse(responseCode = "409", description = "Claim is not editable in its current status.")
    @PreAuthorize("hasAuthority('" + WarrantyPermissions.CLAIM_CREATE + "')")
    @EmitEvent(id = "WARRANTY_CLAIM_UPDATE", apiVersion = "1")
    @PostMapping("/{id}/lines")
    public ResponseEntity<ClaimResponse> addLine(
            @Parameter(description = "Claim UUID", required = true) @PathVariable @NotNull UUID id,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "One failed part or service line with its source provenance and intake"
                                    + " snapshots.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(
                                                            name = "Manual tire line",
                                                            value = CLAIM_LINE_EXAMPLE)))
                    @Valid
                    @NotNull
                    @RequestBody
                    ClaimLineRequest request) {
        return ResponseEntity.ok(claimService.addLine(id, request));
    }

    @Operation(operationId = "removeClaimLine", summary = "Remove claim line", description = """
                    Removes one line from a claim that is still DRAFT or INFO_NEEDED.
                    Use this tool to drop a mistakenly added line; do not use addClaimLine, which appends a line — \
                    there is no line-edit operation, so correcting a line means removing and re-adding it.
                    Preconditions: the claim must exist, be in DRAFT or INFO_NEEDED, and the line must belong to \
                    the claim.
                    Required inputs: id and lineId (UUIDs) as path parameters; there is no request body.
                    Emits a WARRANTY_CLAIM_UPDATE event and republishes the claim snapshot.
                    Returns 404 when the claim does not exist or the line is not on the claim, and 409 when the \
                    claim is not editable in its current status.
                    """)
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

    @Operation(operationId = "addClaimPhoto", summary = "Add photo", description = """
                    Attaches one photo-evidence URL to a claim that is still DRAFT or INFO_NEEDED; a URL that is \
                    already attached is kept once, so the add is idempotent.
                    Use this tool to attach evidence the governing policy may require before submission; do not \
                    use removeClaimPhoto, which detaches a URL, and do not use updateClaim for single-photo \
                    changes because its photoEvidenceUrls field replaces the whole list.
                    Preconditions: the claim must exist and be in DRAFT or INFO_NEEDED.
                    Required inputs: id (UUID) as a path parameter and a body with url (max 2048 characters); the \
                    image itself is stored elsewhere — warranty keeps only the URL reference.
                    Emits a WARRANTY_CLAIM_UPDATE event and republishes the claim snapshot.
                    Returns 404 when the claim does not exist, and 409 when the claim is not editable in its \
                    current status.
                    """)
    @ApiResponse(responseCode = "200", description = "Photo attached.")
    @ApiResponse(responseCode = "404", description = "Claim not found.")
    @ApiResponse(responseCode = "409", description = "Claim is not editable in its current status.")
    @PreAuthorize("hasAuthority('" + WarrantyPermissions.CLAIM_CREATE + "')")
    @EmitEvent(id = "WARRANTY_CLAIM_UPDATE", apiVersion = "1")
    @PostMapping("/{id}/photos")
    public ResponseEntity<ClaimResponse> addPhoto(
            @Parameter(description = "Claim UUID", required = true) @PathVariable @NotNull UUID id,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Photo-evidence URL to attach to the claim (URL-reference pattern; the"
                                    + " image is hosted elsewhere).",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Tire photo", value = """
                                                                    {"url":"https://media.example.com/claims/wc-2026-000123/tire-1.jpg"}
                                                                    """)))
                    @Valid
                    @NotNull
                    @RequestBody
                    ClaimPhotoRequest request) {
        return ResponseEntity.ok(claimService.addPhoto(id, request.url()));
    }

    @Operation(operationId = "removeClaimPhoto", summary = "Remove photo", description = """
                    Detaches one photo-evidence URL from a claim that is still DRAFT or INFO_NEEDED.
                    Use this tool to withdraw a wrongly attached photo; do not use addClaimPhoto, which attaches a \
                    URL, and do not use updateClaim for single-photo changes because its list field is \
                    full-replace.
                    Preconditions: the claim must exist, be in DRAFT or INFO_NEEDED, and the URL must currently be \
                    attached.
                    Required inputs: id (UUID) as a path parameter and url as a query parameter matching the \
                    attached URL exactly; there is no request body.
                    Emits a WARRANTY_CLAIM_UPDATE event and republishes the claim snapshot.
                    Returns 404 when the claim does not exist or the URL is not attached, and 409 when the claim \
                    is not editable in its current status.
                    """)
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

    @Operation(operationId = "submitClaim", summary = "Submit claim", description = """
                    Completes intake by running the eligibility evaluation and then moving the claim DRAFT to \
                    SUBMITTED, or INFO_NEEDED back to IN_REVIEW once the requested information is provided.
                    Use this tool when intake is finished; do not use decideClaim, which records the adjudication \
                    outcome, and do not use evaluateClaimEligibility, which recomputes the suggestion without any \
                    status change.
                    Preconditions: the claim must be DRAFT or INFO_NEEDED, must have at least one claim line, and \
                    must carry at least one photo when the winning policy requires photo evidence.
                    Required inputs: id (UUID) as a path parameter; there is no request body — eligibility, policy \
                    selection, and per-line proration are computed server-side and persisted onto the claim.
                    Emits a WARRANTY_CLAIM_SUBMIT event and republishes the claim snapshot.
                    Returns 422 when the claim has no lines (WARRANTY_CLAIM_MISSING_LINES) or the selected policy \
                    requires missing photo evidence (WARRANTY_CLAIM_PHOTO_EVIDENCE_REQUIRED), 404 when the claim \
                    does not exist, and 409 when the current status does not allow submit (nextAction lists the \
                    legal moves).
                    """)
    @ApiResponse(responseCode = "200", description = "Claim submitted.")
    @ApiResponse(
            responseCode = "422",
            description = "Intake incomplete: no claim line (WARRANTY_CLAIM_MISSING_LINES) or the winning policy"
                    + " requires photo evidence that is missing (WARRANTY_CLAIM_PHOTO_EVIDENCE_REQUIRED).")
    @ApiResponse(responseCode = "404", description = "Claim not found.")
    @ApiResponse(responseCode = "409", description = "Illegal transition; nextAction lists legal moves.")
    @PreAuthorize("hasAuthority('" + WarrantyPermissions.CLAIM_SUBMIT + "')")
    @EmitEvent(id = "WARRANTY_CLAIM_SUBMIT", apiVersion = "1")
    @PostMapping("/{id}/submit")
    public ResponseEntity<ClaimResponse> submitClaim(
            @Parameter(description = "Claim UUID", required = true) @PathVariable @NotNull UUID id) {
        return ResponseEntity.ok(claimService.submit(id));
    }

    @Operation(operationId = "evaluateClaimEligibility", summary = "Re-run eligibility", description = """
                    Recomputes the eligibility suggestion — result, reasons, suggested outcome, policy selection, \
                    and per-line proration — on demand without changing the claim status.
                    Use this tool after intake data changes to refresh the suggestion; do not use submitClaim, \
                    which also runs eligibility but performs the lifecycle move, and do not use decideClaim, which \
                    records the human decision that may override the suggestion.
                    Preconditions: the claim must be DRAFT, SUBMITTED, IN_REVIEW, or INFO_NEEDED — once decided \
                    (APPROVED, DENIED, SETTLED) the persisted inputs are frozen audit evidence, and a DENIED claim \
                    regains recompute only through an appeal back to IN_REVIEW.
                    Required inputs: id (UUID) as a path parameter; there is no request body.
                    Emits a WARRANTY_CLAIM_UPDATE event and republishes the claim snapshot with the recomputed \
                    suggestion.
                    Returns 404 when the claim does not exist, and 409 when the claim is already decided or \
                    terminal.
                    """)
    @ApiResponse(responseCode = "200", description = "Eligibility recomputed.")
    @ApiResponse(responseCode = "404", description = "Claim not found.")
    @ApiResponse(responseCode = "409", description = "Claim already decided or terminal.")
    @PreAuthorize("hasAuthority('" + WarrantyPermissions.CLAIM_VIEW + "')")
    @EmitEvent(id = "WARRANTY_CLAIM_UPDATE", apiVersion = "1")
    @PostMapping("/{id}/eligibility")
    public ResponseEntity<ClaimResponse> evaluateEligibility(
            @Parameter(description = "Claim UUID", required = true) @PathVariable @NotNull UUID id) {
        return ResponseEntity.ok(claimService.evaluateEligibility(id));
    }

    @Operation(operationId = "decideClaim", summary = "Decide claim", description = """
                    Records the human adjudication decision on a claim — APPROVE, DENY, or REQUEST_INFO, where a \
                    decision on a SUBMITTED claim implicitly begins review first — while APPEAL reopens a DENIED \
                    claim back to IN_REVIEW and clears the standing decision (the denial stays audited in the \
                    status history).
                    Use this tool to adjudicate; do not use submitClaim, which moves intake into adjudication, and \
                    do not use createSettlement, which executes the payout only after approval.
                    Preconditions: the claim must be SUBMITTED, IN_REVIEW, or DENIED (APPEAL only); DENY and \
                    APPEAL require a reason, and a decision contradicting the computed suggestion (approving an \
                    INELIGIBLE or denying an ELIGIBLE claim) also requires a reason and marks the claim \
                    overrodeSuggestion.
                    Required inputs: id (UUID) and a body with decision; optional lineDecisions (APPROVE/DENY \
                    only) set per-line amountApproved and lineDisposition, omitted lines default to the computed \
                    amountRequested on approve or to DENIED on deny, and an amountApproved differing from the \
                    computed amount requires an overrideReason that is audited as a claim note.
                    Emits a WARRANTY_CLAIM_DECIDE event and republishes the claim snapshot.
                    Returns 400 when a required reason or overrideReason is missing or lineDecisions accompany \
                    REQUEST_INFO, 404 when the claim or a referenced line does not exist, and 409 when the current \
                    status does not allow the move (nextAction lists the legal moves).
                    """)
    @ApiResponse(responseCode = "200", description = "Decision applied.")
    @ApiResponse(responseCode = "400", description = "Missing required reason.")
    @ApiResponse(responseCode = "404", description = "Claim not found.")
    @ApiResponse(responseCode = "409", description = "Illegal transition; nextAction lists legal moves.")
    @PreAuthorize("hasAuthority('" + WarrantyPermissions.CLAIM_DECIDE + "')")
    @EmitEvent(id = "WARRANTY_CLAIM_DECIDE", apiVersion = "1")
    @PostMapping("/{id}/decision")
    public ResponseEntity<ClaimResponse> decideClaim(
            @Parameter(description = "Claim UUID", required = true) @PathVariable @NotNull UUID id,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Adjudication action with its reason and optional per-line approved"
                                    + " amounts and dispositions.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(
                                                            name = "Approve with line override",
                                                            value = CLAIM_DECISION_EXAMPLE)))
                    @Valid
                    @NotNull
                    @RequestBody
                    ClaimDecisionRequest request) {
        return ResponseEntity.ok(claimService.decide(id, request));
    }

    @Operation(operationId = "cancelClaim", summary = "Cancel claim", description = """
                    Cancels a warranty claim, moving DRAFT, SUBMITTED, or INFO_NEEDED to the terminal CANCELLED \
                    status with an audited status-history row.
                    Use this tool when the customer or staff abandons the claim before adjudication completes; do \
                    not use closeClaim, which finishes a SETTLED or DENIED claim after its child records are \
                    terminal.
                    Preconditions: the claim must be DRAFT, SUBMITTED, or INFO_NEEDED — IN_REVIEW, APPROVED, \
                    DENIED, SETTLED, and terminal claims cannot be cancelled.
                    Required inputs: id (UUID) as a path parameter; the body is optional and carries only a reason \
                    recorded in the status history.
                    Emits a WARRANTY_CLAIM_CANCEL event and republishes the claim snapshot.
                    Returns 404 when the claim does not exist, and 409 when the current status does not allow \
                    cancellation (nextAction lists the legal moves).
                    """)
    @ApiResponse(responseCode = "200", description = "Claim cancelled.")
    @ApiResponse(responseCode = "404", description = "Claim not found.")
    @ApiResponse(responseCode = "409", description = "Illegal transition; nextAction lists legal moves.")
    @PreAuthorize("hasAuthority('" + WarrantyPermissions.CLAIM_CANCEL + "')")
    @EmitEvent(id = "WARRANTY_CLAIM_CANCEL", apiVersion = "1")
    @PostMapping("/{id}/cancel")
    public ResponseEntity<ClaimResponse> cancelClaim(
            @Parameter(description = "Claim UUID", required = true) @PathVariable @NotNull UUID id,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Optional cancellation reason recorded in the claim status history.",
                            required = false,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Cancel with reason", value = """
                                                                    {"reason":"Customer withdrew the claim"}
                                                                    """)))
                    @Valid
                    @RequestBody(required = false)
                    ClaimActionRequest request) {
        return ResponseEntity.ok(claimService.cancel(id, request == null ? new ClaimActionRequest(null) : request));
    }

    @Operation(operationId = "closeClaim", summary = "Close claim", description = """
                    Closes a warranty claim, moving SETTLED or DENIED to the terminal CLOSED status once every \
                    child record is terminal.
                    Use this tool to finish the back-office lifecycle after the customer journey is done; do not \
                    use cancelClaim, which abandons a claim before adjudication — open children block CLOSED but \
                    never SETTLED (settle-customer-first).
                    Preconditions: the claim must be SETTLED or DENIED; every vendor reimbursement must be \
                    terminal (CREDIT_RECEIVED, WRITTEN_OFF, NOT_APPLICABLE, or DENIED), every part return must be \
                    terminal (RECEIVED_BY_VENDOR, SCRAPPED, or CLOSED), and a SETTLED claim whose policy requires \
                    part return must have at least one part return on record.
                    Required inputs: id (UUID) as a path parameter; the body is optional and carries only a reason \
                    recorded in the status history.
                    Emits a WARRANTY_CLAIM_CLOSE event and republishes the claim snapshot.
                    Returns 404 when the claim does not exist, and 409 when the transition is illegal or open \
                    child records block closing (WARRANTY_CLAIM_CLOSE_BLOCKED, with nextAction describing what to \
                    resolve).
                    """)
    @ApiResponse(responseCode = "200", description = "Claim closed.")
    @ApiResponse(responseCode = "404", description = "Claim not found.")
    @ApiResponse(responseCode = "409", description = "Illegal transition or open child records; see nextAction.")
    @PreAuthorize("hasAuthority('" + WarrantyPermissions.CLAIM_CLOSE + "')")
    @EmitEvent(id = "WARRANTY_CLAIM_CLOSE", apiVersion = "1")
    @PostMapping("/{id}/close")
    public ResponseEntity<ClaimResponse> closeClaim(
            @Parameter(description = "Claim UUID", required = true) @PathVariable @NotNull UUID id,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Optional closure reason recorded in the claim status history.",
                            required = false,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Close with reason", value = """
                                                                    {"reason":"Vendor credit received; RMA received by vendor"}
                                                                    """)))
                    @Valid
                    @RequestBody(required = false)
                    ClaimActionRequest request) {
        return ResponseEntity.ok(claimService.close(id, request == null ? new ClaimActionRequest(null) : request));
    }

    @Operation(operationId = "addClaimNote", summary = "Add note", description = """
                    Appends one free-form, append-only staff note to a warranty claim in any status.
                    Use this tool to record context that is not a status change; do not use decideClaim or \
                    updateClaim, which mutate the claim itself — notes are never editable or deletable once \
                    written.
                    Preconditions: the claim must exist; there is no status restriction.
                    Required inputs: id (UUID) as a path parameter and a body with note (non-blank, max 10000 \
                    characters).
                    Emits a WARRANTY_CLAIM_UPDATE event; the claim's own fields and status are untouched.
                    Returns 201 with the full claim detail including the new note, and 404 when the claim does not \
                    exist.
                    """)
    @ApiResponse(responseCode = "201", description = "Note appended.")
    @ApiResponse(responseCode = "404", description = "Claim not found.")
    @PreAuthorize("hasAuthority('" + WarrantyPermissions.CLAIM_CREATE + "')")
    @EmitEvent(id = "WARRANTY_CLAIM_UPDATE", apiVersion = "1")
    @PostMapping("/{id}/notes")
    public ResponseEntity<ClaimResponse> addNote(
            @Parameter(description = "Claim UUID", required = true) @PathVariable @NotNull UUID id,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Free-form staff note text to append to the claim's audit trail.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Staff note", value = """
                                                                    {"note":"Customer called; defective tire is on hold shelf B3"}
                                                                    """)))
                    @Valid
                    @NotNull
                    @RequestBody
                    ClaimNoteRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(claimService.addNote(id, request));
    }
}
