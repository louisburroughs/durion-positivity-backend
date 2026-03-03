package com.positivity.price.internal.controller;

import com.positivity.price.internal.dto.PricingSnapshotResponse;
import com.positivity.price.service.PricingSnapshotService;
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
@RequestMapping("/v1/price/snapshots")
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
    public ResponseEntity<PricingSnapshotResponse> getSnapshot(@PathVariable UUID snapshotId) {
        return ResponseEntity.ok(pricingSnapshotService.getSnapshot(snapshotId));
    }
}
