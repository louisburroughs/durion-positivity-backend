package com.positivity.inventory.service;

import com.positivity.inventory.internal.dto.replenishment.CreateReplenishmentPolicyRequest;
import com.positivity.inventory.internal.dto.replenishment.ReplenishmentNeedResponse;
import com.positivity.inventory.internal.dto.replenishment.ReplenishmentPolicyResponse;
import com.positivity.inventory.internal.dto.replenishment.ReplenishmentScanResultResponse;
import com.positivity.inventory.internal.dto.replenishment.ReplenishmentTaskResponse;
import com.positivity.inventory.internal.dto.replenishment.UpdateReplenishmentPolicyRequest;
import java.time.Instant;
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

    /**
     * Full-replace update of a policy's thresholds and F3 tuning fields (odoo-parity F3,
     * issue #1041). PUT semantics: omitted optional fields are cleared / reset to their
     * defaults; identity (location, SKU) and snooze state are not updatable here.
     */
    @NonNull
    ReplenishmentPolicyResponse updateReplenishmentPolicy(
            @NonNull UUID policyId, @NonNull UpdateReplenishmentPolicyRequest request);

    /**
     * Snoozes a policy until a future instant, or clears an existing snooze when
     * {@code snoozedUntil} is {@code null} (odoo-parity F3, issue #1041 — Odoo orderpoint
     * snooze). A non-null instant that is not in the future is rejected with
     * {@link com.positivity.inventory.internal.exception.SnoozeUntilNotInFutureException}.
     */
    @NonNull
    ReplenishmentPolicyResponse snoozeReplenishmentPolicy(@NonNull UUID policyId, @Nullable Instant snoozedUntil);

    /**
     * Side-effect-free replenishment needs report (odoo-parity F3/F6, issue #1041): the
     * current orderpoint evaluation of every ACTIVE, non-snoozed policy — the same shared
     * math the batch scan applies — without creating or refreshing any task.
     */
    @NonNull
    List<ReplenishmentNeedResponse> getReplenishmentNeeds();
}
