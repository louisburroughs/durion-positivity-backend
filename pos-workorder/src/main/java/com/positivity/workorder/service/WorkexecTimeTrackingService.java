package com.positivity.workorder.service;

import com.positivity.workorder.internal.dto.WorkexecJobTimeTotalResponse;
import com.positivity.workorder.internal.dto.WorkexecLaborPerformedRequest;
import com.positivity.workorder.internal.dto.WorkexecLaborPerformedResponse;
import com.positivity.workorder.internal.dto.WorkexecTimerEntryResponse;
import com.positivity.workorder.internal.dto.WorkexecTimerStartRequest;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

public interface WorkexecTimeTrackingService {

    @NonNull
    List<WorkexecJobTimeTotalResponse> getJobTimeTotals(
            @NonNull LocalDate startDate,
            @NonNull LocalDate endDate,
            @NonNull ZoneId timezone,
            @Nullable UUID locationId,
            @NonNull List<UUID> technicianIds);

    @NonNull
    LaborPerformedResult recordLaborPerformed(
            @NonNull WorkexecLaborPerformedRequest request,
            @NonNull String idempotencyKey);

    @NonNull
    List<WorkexecTimerEntryResponse> getActiveTimers(@NonNull UUID mechanicId);

    @NonNull
    TimerStartResult startTimer(
            @NonNull UUID mechanicId,
            @NonNull WorkexecTimerStartRequest request,
            @Nullable String idempotencyKey);

    @NonNull
    List<WorkexecTimerEntryResponse> stopTimers(@NonNull UUID mechanicId);

    record LaborPerformedResult(WorkexecLaborPerformedResponse response, boolean replayed) {
    }

    record TimerStartResult(WorkexecTimerEntryResponse response, boolean replayed) {
    }

    final class WorkexecConflictException extends RuntimeException {
        private final String code;

        public WorkexecConflictException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String getCode() {
            return code;
        }
    }
}
