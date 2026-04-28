package com.positivity.inventory.service;

import com.positivity.inventory.internal.dto.putaway.GeneratePutawayTasksRequest;
import com.positivity.inventory.internal.dto.putaway.PutawayTaskResponse;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

public interface PutawayGenerationService {

    @NonNull
    List<PutawayTaskResponse> generateTasksForReceipt(@NonNull GeneratePutawayTasksRequest request);

    @NonNull
    List<PutawayTaskResponse> getTasksByReceiptId(@NonNull String receiptId);

    @NonNull
    List<PutawayTaskResponse> getAvailableTasks(@Nullable UUID locationId, @Nullable UUID storageLocationId);

    @NonNull
    PutawayTaskResponse claimTask(@NonNull String taskId, @NonNull String userId);
}
