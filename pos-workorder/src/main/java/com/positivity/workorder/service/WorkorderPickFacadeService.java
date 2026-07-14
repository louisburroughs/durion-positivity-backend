package com.positivity.workorder.service;

import com.positivity.workorder.internal.dto.pick.CompletePickTaskRequest;
import com.positivity.workorder.internal.dto.pick.ConfirmPickLineRequest;
import com.positivity.workorder.internal.dto.pick.ConsumePickedItemsRequest;
import com.positivity.workorder.internal.dto.pick.ConsumePickedItemsResponse;
import com.positivity.workorder.internal.dto.pick.ResolveScanRequest;
import com.positivity.workorder.internal.dto.pick.ResolveScanResponse;
import com.positivity.workorder.internal.dto.pick.WorkorderPickListResponse;
import com.positivity.workorder.internal.dto.pick.WorkorderPickTaskResponse;
import com.positivity.workorder.internal.dto.pick.WorkorderPickedItemResponse;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

public interface WorkorderPickFacadeService {

    /** Response status while an asynchronous pick command is in flight (ADR-0044 R4, #901). */
    String STATUS_PENDING = "PENDING";

    @NonNull
    WorkorderPickListResponse getPickListForWorkorder(@NonNull UUID workorderId);

    @NonNull
    List<WorkorderPickTaskResponse> getPickTasksForWorkorder(@NonNull UUID workorderId);

    @NonNull
    ResolveScanResponse resolveScan(
            @NonNull UUID workorderId, @NonNull UUID pickTaskId, @NonNull ResolveScanRequest request);

    @NonNull
    WorkorderPickTaskResponse confirmPickLine(
            @NonNull UUID workorderId,
            @NonNull UUID pickTaskId,
            @NonNull UUID pickLineId,
            @NonNull ConfirmPickLineRequest request);

    @NonNull
    WorkorderPickTaskResponse completePickTask(
            @NonNull UUID workorderId, @NonNull UUID pickTaskId, @NonNull CompletePickTaskRequest request);

    @NonNull
    List<WorkorderPickedItemResponse> getPickedItemsForWorkorder(@NonNull UUID workorderId);

    @NonNull
    ConsumePickedItemsResponse consumePickedItems(
            @NonNull UUID workorderId, @NonNull ConsumePickedItemsRequest request);
}
