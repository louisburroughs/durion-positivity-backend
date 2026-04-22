package com.positivity.price.internal.controller;

import com.positivity.price.internal.dto.PricingSnapshotResponse;
import com.positivity.price.service.PricingSnapshotService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read API for immutable pricing snapshots.
 *
 * Issue: #50
 */
@RestController
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
@RequestMapping("/v1/price/snapshots")
@Tag(name = "Pricing Snapshots", description = "Read operations for immutable pricing snapshots")
public class PricingSnapshotController {

    private final PricingSnapshotService pricingSnapshotService;

    public PricingSnapshotController(PricingSnapshotService pricingSnapshotService) {
        this.pricingSnapshotService = pricingSnapshotService;
    }

    /**
     * Retrieves an immutable pricing snapshot by ID.
     *
     * @param snapshotId snapshot identifier
     * @return persisted snapshot
     */
    @GetMapping("/{snapshotId}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Get pricing snapshot by ID", description = "Retrieves an immutable pricing snapshot using its snapshot identifier.")
    @ApiResponse(responseCode = "200", description = "Pricing snapshot returned.")
    @ApiResponse(responseCode = "404", description = "Pricing snapshot not found.")
    @ApiResponse(responseCode = "403", description = "Forbidden.")
    public ResponseEntity<PricingSnapshotResponse> getSnapshot(@PathVariable UUID snapshotId) {
        return ResponseEntity.ok(pricingSnapshotService.getSnapshot(snapshotId));
    }
}
