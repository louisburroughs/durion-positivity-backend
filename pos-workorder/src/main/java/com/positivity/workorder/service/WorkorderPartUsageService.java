package com.positivity.workorder.service;

import com.positivity.workorder.internal.dto.WorkorderPartUsageEventResponse;
import com.positivity.workorder.internal.entity.WorkorderPartUsageEvent;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

public interface WorkorderPartUsageService {

    @NonNull
    WorkorderPartUsageEvent issuePartQuantity(
            @NonNull UUID workorderId,
            @NonNull UUID partLineId,
            @NonNull BigDecimal quantity,
            @NonNull UUID performedBy,
            @Nullable String idempotencyKey);

    @NonNull
    WorkorderPartUsageEvent consumePartQuantity(
            @NonNull UUID workorderId,
            @NonNull UUID partLineId,
            @NonNull BigDecimal quantity,
            @NonNull UUID performedBy,
            @Nullable String idempotencyKey);

    @NonNull
    WorkorderPartUsageEvent returnPartQuantity(
            @NonNull UUID workorderId,
            @NonNull UUID partLineId,
            @NonNull BigDecimal quantity,
            @NonNull UUID performedBy,
            @Nullable String idempotencyKey);

    @NonNull
    List<WorkorderPartUsageEventResponse> getUsageHistory(@NonNull UUID workorderId, @NonNull UUID partLineId);

    @NonNull
    List<WorkorderPartUsageEventResponse> getAllUsageHistory(@NonNull UUID workorderId);
}
