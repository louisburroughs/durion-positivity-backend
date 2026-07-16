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

    @Operation(
            summary = "Create part return (RMA)",
            description = "Opens the RMA lifecycle for one claim line (at most one per line); starts AWAITING_PART")
    @ApiResponse(responseCode = "201", description = "Part return created.")
    @ApiResponse(responseCode = "404", description = "Claim or claim line not found.")
    @ApiResponse(responseCode = "409", description = "Line already has a part return, or claim is terminal.")
    @PreAuthorize("hasAuthority('" + WarrantyPermissions.PART_RETURN_MANAGE + "')")
    @EmitEvent(id = "WARRANTY_PART_RETURN_CREATE", apiVersion = "1")
    @PostMapping("/claims/{id}/part-returns")
    public ResponseEntity<PartReturnResponse> createPartReturn(
            @Parameter(description = "Claim id") @PathVariable("id") UUID claimId,
            @Valid @NotNull @RequestBody PartReturnCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(partReturnService.create(claimId, request));
    }

    @Operation(
            summary = "Update part return (RMA)",
            description = "Advances the RMA (AWAITING_PART -> ON_HOLD -> SHIPPED -> RECEIVED_BY_VENDOR, or"
                    + " SCRAPPED/CLOSED per disposition) and/or updates RMA details")
    @ApiResponse(responseCode = "200", description = "Part return updated.")
    @ApiResponse(responseCode = "404", description = "Part return not found.")
    @ApiResponse(responseCode = "409", description = "Illegal part-return transition.")
    @PreAuthorize("hasAuthority('" + WarrantyPermissions.PART_RETURN_MANAGE + "')")
    @EmitEvent(id = "WARRANTY_PART_RETURN_UPDATE", apiVersion = "1")
    @PutMapping("/part-returns/{id}")
    public ResponseEntity<PartReturnResponse> updatePartReturn(
            @Parameter(description = "Part return id") @PathVariable("id") UUID partReturnId,
            @Valid @NotNull @RequestBody PartReturnUpdateRequest request) {
        return ResponseEntity.ok(partReturnService.update(partReturnId, request));
    }

    @Operation(
            summary = "Part-return worklist",
            description = "Hold-shelf / shipping worklist of part returns, filterable by status")
    @ApiResponse(responseCode = "200", description = "Part returns returned.")
    @PreAuthorize("hasAuthority('" + WarrantyPermissions.PART_RETURN_VIEW + "')")
    @GetMapping("/part-returns")
    public ResponseEntity<List<PartReturnResponse>> listPartReturns(
            @Parameter(description = "Filter by part-return status") @RequestParam(required = false)
                    PartReturnStatus status) {
        return ResponseEntity.ok(partReturnService.worklist(status));
    }
}
