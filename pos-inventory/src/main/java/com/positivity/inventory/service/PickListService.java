package com.positivity.inventory.service;

import com.positivity.inventory.internal.dto.picklist.CreatePickListRequest;
import com.positivity.inventory.internal.dto.picklist.PickListResponse;
import com.positivity.inventory.internal.dto.picklist.PickTaskResponse;
import com.positivity.inventory.internal.enums.PickListStatus;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

public interface PickListService {

    @NonNull
    PickListResponse createPickList(@NonNull CreatePickListRequest request);

    @NonNull
    PickListResponse getPickList(@NonNull UUID pickListId);

    @NonNull
    List<PickListResponse> getPickListsForWorkorder(@NonNull UUID workorderId);

    /**
     * Whether the workorder already has a pick list (#1479). Guards generation so a redelivered
     * or repeated generate request does not leave a second list behind for the same job.
     */
    boolean hasPickList(@NonNull UUID workorderId);

    @NonNull
    PickListResponse updatePickListStatus(@NonNull UUID pickListId, @NonNull PickListStatus status);

    @NonNull
    PickListResponse releasePickList(@NonNull UUID pickListId);

    /** Pre-E2 arity: confirms without a lot number (LOT-tracked SKUs will reject — see below). */
    @NonNull
    default PickTaskResponse confirmPickTask(
            @NonNull UUID pickListId,
            @NonNull UUID pickTaskId,
            @NonNull UUID scannedSkuId,
            @NonNull UUID scannedLocationId,
            int quantityPicked) {
        return confirmPickTask(pickListId, pickTaskId, scannedSkuId, scannedLocationId, quantityPicked, null);
    }

    /**
     * Confirms a pick task, optionally recording the lot the units were taken from
     * (odoo-parity E2, issue #1042): LOT-tracked SKUs require a lot number referencing an
     * existing ACTIVE lot; untracked SKUs ignore it.
     */
    @NonNull
    PickTaskResponse confirmPickTask(
            @NonNull UUID pickListId,
            @NonNull UUID pickTaskId,
            @NonNull UUID scannedSkuId,
            @NonNull UUID scannedLocationId,
            int quantityPicked,
            @Nullable String lotNumber);

    @NonNull
    List<PickTaskResponse> getPickTasksForPickList(@NonNull UUID pickListId);

    void cancelPickList(@NonNull UUID pickListId);
}
