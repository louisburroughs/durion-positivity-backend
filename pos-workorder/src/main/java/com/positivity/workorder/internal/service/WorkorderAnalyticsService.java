package com.positivity.workorder.internal.service;

import com.positivity.workorder.internal.dto.ReopenedWorkorderAnalyticsResponse;
import com.positivity.workorder.internal.dto.TechnicianLaborAnalyticsResponse;
import com.positivity.workorder.internal.dto.WorkorderStatusTransitionsResponse;
import com.positivity.workorder.internal.enums.WorkorderStatus;
import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/** Wave 2 analytics endpoints for pos-workorder (E5 #1593, E6 #1594, E7 #1595). */
public interface WorkorderAnalyticsService {

    /**
     * E7: either {@code woId} alone, or the ({@code from}, {@code to}, {@code startDate},
     * {@code endDate}) range form — mutually exclusive, validated by the caller-facing controller
     * before this method is invoked defensively as well.
     */
    @NonNull
    WorkorderStatusTransitionsResponse getStatusTransitions(
            @Nullable UUID woId,
            @Nullable WorkorderStatus from,
            @Nullable WorkorderStatus to,
            @Nullable LocalDate startDate,
            @Nullable LocalDate endDate,
            int limit);

    /** E6: reopen events within {@code withinDays} of completion, anchored on the completion date. */
    @NonNull
    ReopenedWorkorderAnalyticsResponse getReopenedWorkorders(
            @NonNull LocalDate startDate, @NonNull LocalDate endDate, int withinDays, int limit);

    /** E5: per-technician labor summary for the window. */
    @NonNull
    TechnicianLaborAnalyticsResponse getTechnicianLabor(
            @NonNull LocalDate startDate, @NonNull LocalDate endDate, int limit);
}
