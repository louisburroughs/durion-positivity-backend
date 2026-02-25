package com.positivity.inventory.service;

import com.positivity.inventory.internal.dto.picklist.CreatePickListRequest;
import com.positivity.inventory.internal.dto.picklist.PickListResponse;
import com.positivity.inventory.internal.dto.picklist.PickTaskResponse;
import com.positivity.inventory.internal.enums.PickListStatus;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

public interface PickListService {

    @NonNull
    PickListResponse createPickList(@NonNull CreatePickListRequest request);

    @NonNull
    PickListResponse getPickList(@NonNull UUID pickListId);

    @NonNull
    List<PickListResponse> getPickListsForWorkorder(@NonNull UUID workorderId);

    @NonNull
    PickListResponse updatePickListStatus(@NonNull UUID pickListId, @NonNull PickListStatus status);

    @NonNull
    PickListResponse releasePickList(@NonNull UUID pickListId);

    @NonNull
    PickTaskResponse confirmPickTask(
            @NonNull UUID pickListId,
            @NonNull UUID pickTaskId,
            @NonNull UUID scannedSkuId,
            @NonNull UUID scannedLocationId,
            int quantityPicked);

    @NonNull
    List<PickTaskResponse> getPickTasksForPickList(@NonNull UUID pickListId);

    void cancelPickList(@NonNull UUID pickListId);
}