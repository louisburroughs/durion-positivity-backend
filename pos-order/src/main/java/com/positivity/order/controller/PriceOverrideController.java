package com.positivity.order.controller;

import com.positivity.order.dto.*;
import com.positivity.order.exception.InsufficientPermissionException;
import com.positivity.order.model.OverrideStatus;
import com.positivity.order.model.PriceOverride;
import com.positivity.order.security.PriceOverridePermissions;
import com.positivity.order.service.PriceOverrideService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

/**
 * REST controller for price override operations.
 * 
 * Provides endpoints for:
 * - Applying price overrides
 * - Approving/rejecting overrides
 * - Querying override history
 */
@Tag(name = "Price Overrides", description = "Price override management with approval workflow")
@RestController
@RequestMapping("/api/v1/orders/price-overrides")
@RequiredArgsConstructor
@Slf4j
public class PriceOverrideController {

    private final PriceOverrideService priceOverrideService;

    /**
     * Apply a price override to an order line.
     * Requires PRICE_OVERRIDE_APPLY permission.
     */
    @Operation(summary = "Apply price override", description = "Apply a price override to an order line. May require approval based on override amount.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Override applied and auto-approved", content = @Content(schema = @Schema(implementation = ApplyPriceOverrideResponse.class))),
            @ApiResponse(responseCode = "202", description = "Override applied, pending approval", content = @Content(schema = @Schema(implementation = ApplyPriceOverrideResponse.class))),
            @ApiResponse(responseCode = "400", description = "Invalid request"),
            @ApiResponse(responseCode = "403", description = "Insufficient permissions")
    })
    @PostMapping
    @PreAuthorize("hasAuthority('" + PriceOverridePermissions.PRICE_OVERRIDE_APPLY + "')")
    public ResponseEntity<ApplyPriceOverrideResponse> applyPriceOverride(
            @Valid @RequestBody ApplyPriceOverrideRequest request,
            Authentication authentication) {

        String userId = authentication.getName();
        log.info("User {} applying price override for order {}", userId, request.getOrderId());

        ApplyPriceOverrideResponse response = priceOverrideService.applyPriceOverride(request, userId);

        HttpStatus status = response.getRequiresApproval() ? HttpStatus.ACCEPTED : HttpStatus.CREATED;

        return ResponseEntity.status(status).body(response);
    }

    /**
     * Approve a pending price override.
     * Requires PRICE_OVERRIDE_APPROVE permission.
     */
    @Operation(summary = "Approve price override", description = "Approve a pending price override. Validates approver permission level.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Override approved", content = @Content(schema = @Schema(implementation = PriceOverride.class))),
            @ApiResponse(responseCode = "400", description = "Invalid request or override not in pending state"),
            @ApiResponse(responseCode = "403", description = "Insufficient approval permissions"),
            @ApiResponse(responseCode = "404", description = "Override not found")
    })
    @PostMapping("/{overrideId}/approve")
    @PreAuthorize("hasAuthority('" + PriceOverridePermissions.PRICE_OVERRIDE_APPROVE + "')")
    public ResponseEntity<PriceOverride> approvePriceOverride(
            @Parameter(description = "Price override ID", required = true) @PathVariable Long overrideId,
            @Valid @RequestBody ApprovePriceOverrideRequest request,
            Authentication authentication) {

        String userId = authentication.getName();
        String role = extractPrimaryRole(authentication);

        log.info("User {} ({}) approving price override {}", userId, role, overrideId);

        PriceOverride override = priceOverrideService.approvePriceOverride(
                overrideId, request, userId, role);

        return ResponseEntity.ok(override);
    }

    /**
     * Reject a pending price override.
     * Requires PRICE_OVERRIDE_REJECT permission.
     */
    @Operation(summary = "Reject price override", description = "Reject a pending price override with a reason.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Override rejected", content = @Content(schema = @Schema(implementation = PriceOverride.class))),
            @ApiResponse(responseCode = "400", description = "Invalid request or override not in pending state"),
            @ApiResponse(responseCode = "403", description = "Insufficient rejection permissions"),
            @ApiResponse(responseCode = "404", description = "Override not found")
    })
    @PostMapping("/{overrideId}/reject")
    @PreAuthorize("hasAuthority('" + PriceOverridePermissions.PRICE_OVERRIDE_REJECT + "')")
    public ResponseEntity<PriceOverride> rejectPriceOverride(
            @Parameter(description = "Price override ID", required = true) @PathVariable Long overrideId,
            @Valid @RequestBody RejectPriceOverrideRequest request,
            Authentication authentication) {

        String userId = authentication.getName();
        String role = extractPrimaryRole(authentication);

        log.info("User {} ({}) rejecting price override {}", userId, role, overrideId);

        PriceOverride override = priceOverrideService.rejectPriceOverride(
                overrideId, request, userId, role);

        return ResponseEntity.ok(override);
    }

    /**
     * Get a specific price override by ID.
     * Requires PRICE_OVERRIDE_VIEW permission.
     */
    @Operation(summary = "Get price override", description = "Retrieve a specific price override by ID.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Override found", content = @Content(schema = @Schema(implementation = PriceOverride.class))),
            @ApiResponse(responseCode = "404", description = "Override not found")
    })
    @GetMapping("/{overrideId}")
    @PreAuthorize("hasAuthority('" + PriceOverridePermissions.PRICE_OVERRIDE_VIEW + "')")
    public ResponseEntity<PriceOverride> getOverride(
            @Parameter(description = "Price override ID", required = true) @PathVariable Long overrideId) {
        PriceOverride override = priceOverrideService.getOverrideById(overrideId);
        return ResponseEntity.ok(override);
    }

    /**
     * Get all price overrides for an order.
     * Requires PRICE_OVERRIDE_VIEW permission.
     */
    @Operation(summary = "Get price overrides", description = "Retrieve price overrides by order ID, status, or date range. At least one filter parameter is required.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Overrides retrieved"),
            @ApiResponse(responseCode = "400", description = "No filter parameter provided")
    })
    @GetMapping
    @PreAuthorize("hasAuthority('" + PriceOverridePermissions.PRICE_OVERRIDE_VIEW + "')")
    public ResponseEntity<List<PriceOverride>> getOverridesByOrder(
            @Parameter(description = "Order ID filter") @RequestParam(required = false) String orderId,
            @Parameter(description = "Override status filter") @RequestParam(required = false) OverrideStatus status,
            @Parameter(description = "Start date for date range filter") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant startDate,
            @Parameter(description = "End date for date range filter") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant endDate) {

        List<PriceOverride> overrides;

        if (orderId != null) {
            overrides = priceOverrideService.getOverridesByOrderId(orderId);
        } else if (status != null) {
            overrides = priceOverrideService.getOverridesByStatus(status);
        } else if (startDate != null && endDate != null) {
            overrides = priceOverrideService.getOverridesByDateRange(startDate, endDate);
        } else {
            throw new IllegalArgumentException("At least one filter parameter is required");
        }

        return ResponseEntity.ok(overrides);
    }

    /**
     * Get all pending approval overrides.
     * Requires PRICE_OVERRIDE_APPROVE permission.
     */
    @Operation(summary = "Get pending approvals", description = "Retrieve all price overrides awaiting approval.")
    @ApiResponse(responseCode = "200", description = "Pending overrides retrieved")
    @GetMapping("/pending")
    @PreAuthorize("hasAuthority('" + PriceOverridePermissions.PRICE_OVERRIDE_APPROVE + "')")
    public ResponseEntity<List<PriceOverride>> getPendingApprovals() {
        List<PriceOverride> overrides = priceOverrideService.getPendingApprovals();
        return ResponseEntity.ok(overrides);
    }

    /**
     * Extract the primary role from authentication.
     */
    private String extractPrimaryRole(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(auth -> auth.startsWith("ROLE_"))
                .findFirst()
                .orElse("UNKNOWN");
    }
}
