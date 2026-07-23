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

    /**
     * Event-path orderpoint evaluation for one SKU/pick-face (odoo-parity F2, issue
     * #1040): triggers when {@code projectedAvailable(leadHorizon) < minimumQuantity} and
     * creates an EVENT-triggered task for {@code max(0, maximumQuantity -
     * projectedAvailable(leadHorizon) - inProgress)}; an already-open task short-circuits
     * to {@code TASK_ALREADY_QUEUED} (no double-ordering).
     */
    @NonNull
    ReplenishmentTaskResponse evaluatePickFaceForReplenishment(
            @NonNull String productId, @NonNull UUID pickFaceLocationId);

    /**
     * Runs the batch replenishment scan over all policies (CAP-217 / odoo-parity
     * F1; forecast-aware orderpoint math since F2, issue #1040): policies whose
     * projected available at the lead-time horizon is below minimum get a
     * batch-triggered replenishment task, idempotent per (policy, day).
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
