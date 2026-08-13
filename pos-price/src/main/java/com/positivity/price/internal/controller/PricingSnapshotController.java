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
    @Operation(operationId = "getPricingSnapshotById", summary = "Get Pricing Snapshot By ID", description = """
                    Returns an immutable pricing snapshot that captured the prices, applied rules, and policy version \
                    of a past pricing decision.
                    Use this tool to audit or re-display exactly what was priced at capture time; do not use \
                    calculatePriceQuote, which computes a fresh price that may differ from the recorded one.
                    Preconditions: a snapshot with the supplied id must have been persisted by an earlier pricing flow.
                    Required inputs: snapshotId (UUID) as a path parameter; there is no request body.
                    No events are emitted and no state changes; snapshots are immutable and this is a read-only \
                    projection.
                    Returns 404 with code SNAPSHOT_NOT_FOUND when no snapshot exists for the supplied id.
                    """)
    @ApiResponse(responseCode = "200", description = "Pricing snapshot returned.")
    @ApiResponse(responseCode = "404", description = "Pricing snapshot not found.")
    @ApiResponse(responseCode = "403", description = "Forbidden.")
    public ResponseEntity<PricingSnapshotResponse> getSnapshot(@PathVariable UUID snapshotId) {
        return ResponseEntity.ok(pricingSnapshotService.getSnapshot(snapshotId));
    }
}
