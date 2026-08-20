package com.positivity.inventory.service;

import com.positivity.inventory.internal.dto.cyclecount.tolerance.CreateCycleCountToleranceRequest;
import com.positivity.inventory.internal.dto.cyclecount.tolerance.CycleCountToleranceResponse;
import com.positivity.inventory.internal.dto.cyclecount.tolerance.UpdateCycleCountToleranceRequest;
import java.util.List;
import java.util.UUID;

/**
 * Admin CRUD for cycle-count tolerance configuration (ADR-0055 stage 4, #1416; issue #1414 owner
 * spec: "Tolerance should be configurable by product and storage location, typically as an
 * absolute quantity, percentage, or both").
 */
public interface CycleCountToleranceService {

    /**
     * Creates a tolerance configuration. Rejects a duplicate active row at the same
     * (productId, storageLocation) scope.
     */
    CycleCountToleranceResponse create(CreateCycleCountToleranceRequest request);

    /** Replaces a tolerance configuration's bounds and active state. Scope is immutable. */
    CycleCountToleranceResponse update(UUID toleranceId, UpdateCycleCountToleranceRequest request);

    /** Retrieves one tolerance configuration by id. */
    CycleCountToleranceResponse get(UUID toleranceId);

    /** Lists every tolerance configuration, active and inactive, newest first. */
    List<CycleCountToleranceResponse> list();

    /** Permanently removes a tolerance configuration. */
    void delete(UUID toleranceId);
}
