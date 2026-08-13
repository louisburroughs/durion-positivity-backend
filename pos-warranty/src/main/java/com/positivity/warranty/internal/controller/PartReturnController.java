package com.positivity.warranty.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.warranty.internal.dto.PartReturnCreateRequest;
import com.positivity.warranty.internal.dto.PartReturnResponse;
import com.positivity.warranty.internal.dto.PartReturnUpdateRequest;
import com.positivity.warranty.internal.enums.PartReturnStatus;
import com.positivity.warranty.internal.security.WarrantyPermissions;
import com.positivity.warranty.internal.service.PartReturnService;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Defective-part RMA lifecycle endpoints (PRD §3.8, §8): open a part return for a claim line,
 * advance/annotate it (hold, ship, receive, scrap, close), and the hold-shelf/shipping worklist.
 * Thin controller — all rules live in {@code PartReturnService}; illegal lifecycle moves surface
 * as 409 {@code ApiError} with {@code nextAction} listing the legal moves.
 */
@Tag(name = "Warranty Part Returns", description = "Defective-part RMA lifecycle and worklist")
@RestController
@RequiredArgsConstructor
@Validated
@SecurityRequirement(name = "bearerAuth")
@RequestMapping("/v1/warranty")
public class PartReturnController {

    private final PartReturnService partReturnService;

    @Operation(operationId = "createPartReturn", summary = "Create part return (RMA)", description = """
                    Opens the defective-part RMA lifecycle for one claim line, creating a part return in \
                    AWAITING_PART with the chosen disposition.
                    Use this tool when the governing policy requires the part back or staff choose to hold or \
                    return it; do not use updatePartReturn, which advances or annotates an RMA that already \
                    exists — at most one part return is allowed per claim line.
                    Preconditions: the claim must exist and not be CANCELLED or CLOSED, the line must belong to \
                    the claim, and no part return may already exist for that line; no policy check gates creation \
                    because staff choice is always permitted.
                    Required inputs: claim id (UUID) as a path parameter and a body with claimLineId (UUID) and \
                    disposition (HOLD_FOR_INSPECTION, RETURN_TO_VENDOR, SCRAP_AUTHORIZED, or CUSTOMER_RETAINED); \
                    rmaNumber and holdLocationNote are optional.
                    Emits a WARRANTY_PART_RETURN_CREATE event, enqueues warranty.part-return.requested so \
                    pos-inventory can quarantine the defective unit, and republishes the claim snapshot.
                    Returns 404 when the claim or the line cannot be found, and 409 when the line already has a \
                    part return or the claim is terminal.
                    """)
    @ApiResponse(responseCode = "201", description = "Part return created.")
    @ApiResponse(responseCode = "404", description = "Claim or claim line not found.")
    @ApiResponse(responseCode = "409", description = "Line already has a part return, or claim is terminal.")
    @PreAuthorize("hasAuthority('" + WarrantyPermissions.PART_RETURN_MANAGE + "')")
    @EmitEvent(id = "WARRANTY_PART_RETURN_CREATE", apiVersion = "1")
    @PostMapping("/claims/{id}/part-returns")
    public ResponseEntity<PartReturnResponse> createPartReturn(
            @Parameter(description = "Claim id") @PathVariable("id") UUID claimId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Claim line whose defective part is being returned or held, with its"
                                    + " disposition and optional RMA details.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Return to vendor", value = """
                                                                    {"claimLineId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a07",
                                                                     "disposition":"RETURN_TO_VENDOR",
                                                                     "rmaNumber":"RMA-88231",
                                                                     "holdLocationNote":"Hold shelf B3"}
                                                                    """)))
                    @Valid
                    @NotNull
                    @RequestBody
                    PartReturnCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(partReturnService.create(claimId, request));
    }

    @Operation(operationId = "updatePartReturn", summary = "Update part return (RMA)", description = """
                    Advances a part return through its child state machine — AWAITING_PART to ON_HOLD to SHIPPED \
                    to RECEIVED_BY_VENDOR, or to SCRAPPED/CLOSED per disposition, where ON_HOLD may be skipped — \
                    and/or updates RMA details without a lifecycle move.
                    Use this tool for every change after creation; do not use createPartReturn, which opens a new \
                    RMA and rejects a line that already has one.
                    Preconditions: the part return must exist and not be terminal; moving to SHIPPED or \
                    RECEIVED_BY_VENDOR requires disposition RETURN_TO_VENDOR, and moving to SCRAPPED requires \
                    disposition SCRAP_AUTHORIZED (either already set or set in the same request).
                    Required inputs: part return id (UUID) as a path parameter; every body field is optional — \
                    omit status for a detail-only update, and shippedAt defaults to now when entering SHIPPED.
                    Emits a WARRANTY_PART_RETURN_UPDATE event, enqueues warranty.part-return.shipped when the \
                    return enters SHIPPED, and republishes the claim snapshot.
                    Returns 404 when the part return does not exist, and 409 when the transition is illegal, the \
                    disposition does not permit the target status, or the return is already terminal.
                    """)
    @ApiResponse(responseCode = "200", description = "Part return updated.")
    @ApiResponse(responseCode = "404", description = "Part return not found.")
    @ApiResponse(responseCode = "409", description = "Illegal part-return transition.")
    @PreAuthorize("hasAuthority('" + WarrantyPermissions.PART_RETURN_MANAGE + "')")
    @EmitEvent(id = "WARRANTY_PART_RETURN_UPDATE", apiVersion = "1")
    @PutMapping("/part-returns/{id}")
    public ResponseEntity<PartReturnResponse> updatePartReturn(
            @Parameter(description = "Part return id") @PathVariable("id") UUID partReturnId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Lifecycle move and/or RMA detail changes; all fields optional, omit"
                                    + " status for a detail-only update.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Ship to vendor", value = """
                                                                    {"status":"SHIPPED",
                                                                     "disposition":"RETURN_TO_VENDOR",
                                                                     "carrier":"UPS",
                                                                     "trackingNumber":"1Z999AA10123456784",
                                                                     "shippedAt":"2026-08-12T15:04:05Z"}
                                                                    """)))
                    @Valid
                    @NotNull
                    @RequestBody
                    PartReturnUpdateRequest request) {
        return ResponseEntity.ok(partReturnService.update(partReturnId, request));
    }

    @Operation(operationId = "listPartReturns", summary = "Part-return worklist", description = """
                    Returns the hold-shelf and shipping worklist of part returns across all claims, optionally \
                    filtered by status.
                    Use this tool to drive the back-office RMA worklist; use getClaim instead to see the part \
                    returns of one specific claim.
                    Preconditions: none — an empty list is returned when nothing matches.
                    Required inputs: status is an optional query parameter (AWAITING_PART, ON_HOLD, SHIPPED, \
                    RECEIVED_BY_VENDOR, SCRAPPED, CLOSED); omitting it returns every part return.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 200 with the list, which is empty rather than 404 when no part return matches.
                    """)
    @ApiResponse(responseCode = "200", description = "Part returns returned.")
    @PreAuthorize("hasAuthority('" + WarrantyPermissions.PART_RETURN_VIEW + "')")
    @GetMapping("/part-returns")
    public ResponseEntity<List<PartReturnResponse>> listPartReturns(
            @Parameter(description = "Filter by part-return status") @RequestParam(required = false)
                    PartReturnStatus status) {
        return ResponseEntity.ok(partReturnService.worklist(status));
    }
}
