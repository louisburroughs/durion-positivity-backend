package com.positivity.workorder.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

public interface WorkexecTimeTrackingService {

    @NonNull
    List<JobTimeTotal> getJobTimeTotals(
            @NonNull LocalDate startDate,
            @NonNull LocalDate endDate,
            @NonNull ZoneId timezone,
            @Nullable UUID locationId,
            @NonNull List<UUID> technicianIds);

    @NonNull
    LaborPerformedResult recordLaborPerformed(
            @NonNull LaborPerformedRequest request,
            @NonNull String idempotencyKey);

    @NonNull
    List<TimerEntry> getActiveTimers(@NonNull UUID mechanicId);

    @NonNull
    TimerStartResult startTimer(
            @NonNull UUID mechanicId,
            @NonNull TimerStartRequest request,
            @Nullable String idempotencyKey);

    @NonNull
    List<TimerEntry> stopTimers(@NonNull UUID mechanicId);

    record JobTimeTotal(
            UUID technicianId,
            UUID locationId,
            LocalDate localDate,
            Integer totalJobMinutes) {
    }

    record LaborQuantity(BigDecimal quantity, String unit) {
    }

    record SourceReference(String system, String sourceReferenceId) {
    }

    record LaborPerformedRequest(
            UUID workorderId,
            UUID technicianId,
            Instant performedAt,
            LaborQuantity labor,
            SourceReference source) {
    }

    record LaborPerformedResponse(
            UUID laborPerformedId,
            UUID workorderId,
            UUID technicianId,
            Instant performedAt,
            BigDecimal quantity,
            String unit,
            String sourceSystem,
            String sourceReferenceId) {
    }

    record LaborPerformedResult(LaborPerformedResponse response, boolean replayed) {
    }

    record TimerStartRequest(
            UUID workorderId,
            UUID workorderItemId,
            String laborCode) {
    }

    record TimerEntry(
            UUID timeEntryId,
            UUID mechanicId,
            UUID workorderId,
            UUID workorderItemId,
            String laborCode,
            LocalDateTime startTime,
            LocalDateTime endTime,
            Long durationInSeconds,
            String status) {
    }

    record TimerStartResult(TimerEntry response, boolean replayed) {
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
