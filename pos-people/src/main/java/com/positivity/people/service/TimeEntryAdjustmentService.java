package com.positivity.people.service;

import com.positivity.people.internal.dto.TimeEntryAdjustmentRequest;
import com.positivity.people.internal.dto.TimeEntryAdjustmentResponse;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

public interface TimeEntryAdjustmentService {

    @NonNull
    TimeEntryAdjustmentResponse createAdjustment(@NonNull TimeEntryAdjustmentRequest request);

    @NonNull
    List<com.positivity.people.internal.dto.TimeEntryAdjustment> listForTimeEntry(@NonNull String timeEntryId);

    boolean approveAdjustment(
            @NonNull UUID adjustmentId,
            @NonNull String approverUserId,
            Set<String> permissions,
            String correlationId);
}
