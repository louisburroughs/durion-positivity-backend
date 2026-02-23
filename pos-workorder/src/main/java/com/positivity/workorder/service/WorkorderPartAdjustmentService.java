package com.positivity.workorder.service;

import com.positivity.workorder.internal.dto.WorkorderPartAdjustmentEventResponse;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

public interface WorkorderPartAdjustmentService {

    @NonNull
    WorkorderPartAdjustmentEventResponse substitutePartLine(
            @NonNull UUID workorderId,
            @NonNull UUID originalPartId,
            @NonNull UUID substitutePartId,
            @NonNull String reason,
            @NonNull UUID performedBy,
            @Nullable String idempotencyKey,
            @Nullable String notes);

    @NonNull
    WorkorderPartAdjustmentEventResponse returnUnusedQuantity(
            @NonNull UUID workorderId,
            @NonNull UUID partId,
            @NonNull BigDecimal quantity,
            @NonNull String reason,
            @NonNull UUID performedBy,
            @Nullable String idempotencyKey,
            @Nullable String notes);

    @NonNull
    WorkorderPartAdjustmentEventResponse correctPartQuantity(
            @NonNull UUID workorderId,
            @NonNull UUID partId,
            @NonNull BigDecimal newQuantity,
            @NonNull String reason,
            @NonNull UUID performedBy,
            @Nullable String idempotencyKey,
            @Nullable String notes);

    @NonNull
    List<WorkorderPartAdjustmentEventResponse> getAdjustmentHistory(@NonNull UUID workorderId);

    @NonNull
    List<WorkorderPartAdjustmentEventResponse> getPartAdjustmentHistory(@NonNull UUID partId);
}
