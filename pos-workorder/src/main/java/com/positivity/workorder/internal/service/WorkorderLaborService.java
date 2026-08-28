package com.positivity.workorder.internal.service;

import com.positivity.workorder.internal.dto.WorkorderLaborEntryResponse;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

public interface WorkorderLaborService {

    @NonNull
    WorkorderLaborEntryResponse startLaborSession(
            @NonNull UUID workorderId,
            @NonNull UUID serviceId,
            @NonNull UUID technicianId,
            @Nullable String notes,
            @NonNull String startedBy,
            @Nullable String idempotencyKey);

    @NonNull
    WorkorderLaborEntryResponse stopLaborSession(@NonNull UUID laborEntryId, @Nullable String idempotencyKey);

    @NonNull
    List<WorkorderLaborEntryResponse> getLaborHistory(@NonNull UUID workorderId);

    @NonNull
    WorkorderLaborEntryResponse adjustLaborHours(
            @NonNull UUID laborEntryId,
            @NonNull BigDecimal adjustedHours,
            @NonNull String adjustmentReason,
            @NonNull String adjustedBy,
            @Nullable String idempotencyKey);
}
