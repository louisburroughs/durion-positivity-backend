package com.positivity.order.controller;

import com.positivity.order.dto.*;
import com.positivity.order.exception.InsufficientPermissionException;
import com.positivity.order.model.OverrideStatus;
import com.positivity.order.model.PriceOverride;
import com.positivity.order.security.PriceOverridePermissions;
import com.positivity.order.service.PriceOverrideService;
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
    @PostMapping
    @PreAuthorize("hasAuthority('" + PriceOverridePermissions.PRICE_OVERRIDE_APPLY + "')")
    public ResponseEntity<ApplyPriceOverrideResponse> applyPriceOverride(
            @Valid @RequestBody ApplyPriceOverrideRequest request,
            Authentication authentication) {
        
        String userId = authentication.getName();
        log.info("User {} applying price override for order {}", userId, request.getOrderId());
        
        ApplyPriceOverrideResponse response = priceOverrideService.applyPriceOverride(request, userId);
        
        HttpStatus status = response.getRequiresApproval() ? 
                HttpStatus.ACCEPTED : HttpStatus.CREATED;
        
        return ResponseEntity.status(status).body(response);
    }
    
    /**
     * Approve a pending price override.
     * Requires PRICE_OVERRIDE_APPROVE permission.
     */
    @PostMapping("/{overrideId}/approve")
    @PreAuthorize("hasAuthority('" + PriceOverridePermissions.PRICE_OVERRIDE_APPROVE + "')")
    public ResponseEntity<PriceOverride> approvePriceOverride(
            @PathVariable Long overrideId,
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
    @PostMapping("/{overrideId}/reject")
    @PreAuthorize("hasAuthority('" + PriceOverridePermissions.PRICE_OVERRIDE_REJECT + "')")
    public ResponseEntity<PriceOverride> rejectPriceOverride(
            @PathVariable Long overrideId,
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
    @GetMapping("/{overrideId}")
    @PreAuthorize("hasAuthority('" + PriceOverridePermissions.PRICE_OVERRIDE_VIEW + "')")
    public ResponseEntity<PriceOverride> getOverride(@PathVariable Long overrideId) {
        PriceOverride override = priceOverrideService.getOverrideById(overrideId);
        return ResponseEntity.ok(override);
    }
    
    /**
     * Get all price overrides for an order.
     * Requires PRICE_OVERRIDE_VIEW permission.
     */
    @GetMapping
    @PreAuthorize("hasAuthority('" + PriceOverridePermissions.PRICE_OVERRIDE_VIEW + "')")
    public ResponseEntity<List<PriceOverride>> getOverridesByOrder(
            @RequestParam(required = false) String orderId,
            @RequestParam(required = false) OverrideStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant endDate) {
        
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
