package com.positivity.workorder.service;

import com.positivity.workorder.internal.entity.WorkorderLaborEntry;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

public interface WorkorderLaborService {

        @NonNull
        WorkorderLaborEntry startLaborSession(
                        @NonNull UUID workorderId,
                        @NonNull UUID serviceId,
                        @NonNull UUID technicianId,
                        @Nullable String notes,
                        @NonNull String startedBy,
                        @Nullable String idempotencyKey);

        @NonNull
        WorkorderLaborEntry stopLaborSession(
                        @NonNull UUID laborEntryId,
                        @Nullable String idempotencyKey);

        @NonNull
        List<WorkorderLaborEntry> getLaborHistory(@NonNull UUID workorderId);

        @NonNull
        WorkorderLaborEntry adjustLaborHours(
                        @NonNull UUID laborEntryId,
                        @NonNull BigDecimal adjustedHours,
                        @NonNull String adjustmentReason,
                        @NonNull String adjustedBy,
                        @Nullable String idempotencyKey);
}
