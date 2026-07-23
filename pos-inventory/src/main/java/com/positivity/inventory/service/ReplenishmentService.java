package com.positivity.inventory.service;

import com.positivity.inventory.internal.dto.replenishment.CreateReplenishmentPolicyRequest;
import com.positivity.inventory.internal.dto.replenishment.ReplenishmentPolicyResponse;
import com.positivity.inventory.internal.dto.replenishment.ReplenishmentScanResultResponse;
import com.positivity.inventory.internal.dto.replenishment.ReplenishmentTaskResponse;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface ReplenishmentService {

    @NonNull
    ReplenishmentTaskResponse evaluatePickFaceForReplenishment(
            @NonNull String productId, @NonNull UUID pickFaceLocationId);

    /**
     * Runs the batch replenishment scan over all policies (CAP-217 / odoo-parity
     * F1): below-minimum policies get a batch-triggered replenishment task,
     * idempotent per (policy, day).
     */
    @NonNull
    ReplenishmentScanResultResponse runBatchReplenishmentScan();

    @NonNull
    List<ReplenishmentTaskResponse> getReplenishmentTasks();

    @NonNull
    Page<ReplenishmentPolicyResponse> getReplenishmentPolicies(@Nullable UUID locationId, @NonNull Pageable pageable);

    @NonNull
    ReplenishmentPolicyResponse createReplenishmentPolicy(@NonNull CreateReplenishmentPolicyRequest request);
}
