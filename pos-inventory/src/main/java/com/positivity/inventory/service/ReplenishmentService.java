package com.positivity.inventory.service;

import com.positivity.inventory.internal.dto.replenishment.CreateReplenishmentPolicyRequest;
import com.positivity.inventory.internal.dto.replenishment.ReplenishmentPolicyResponse;
import com.positivity.inventory.internal.dto.replenishment.ReplenishmentTaskResponse;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

public interface ReplenishmentService {

    @NonNull
    ReplenishmentTaskResponse evaluatePickFaceForReplenishment(
            @NonNull String productId, @NonNull UUID pickFaceLocationId);

    @NonNull
    List<ReplenishmentTaskResponse> runBatchReplenishmentScan();

    @NonNull
    List<ReplenishmentTaskResponse> getReplenishmentTasks();

    @NonNull
    List<ReplenishmentPolicyResponse> getReplenishmentPolicies();

    @NonNull
    ReplenishmentPolicyResponse createReplenishmentPolicy(@NonNull CreateReplenishmentPolicyRequest request);
}
