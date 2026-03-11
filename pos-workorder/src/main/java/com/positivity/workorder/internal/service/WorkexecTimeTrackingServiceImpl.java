package com.positivity.workorder.internal.service;

import java.time.Clock;

import com.positivity.security.common.SecurityContextHelper;
import com.positivity.workorder.internal.dto.WorkexecJobTimeTotalResponse;
import com.positivity.workorder.internal.dto.WorkexecLaborPerformedRequest;
import com.positivity.workorder.internal.dto.WorkexecLaborPerformedResponse;
import com.positivity.workorder.internal.dto.WorkexecTimerEntryResponse;
import com.positivity.workorder.internal.dto.WorkexecTimerStartRequest;
import com.positivity.workorder.internal.entity.Workorder;
import com.positivity.workorder.internal.entity.WorkorderLaborEntry;
import com.positivity.workorder.internal.entity.WorkorderService;
import com.positivity.workorder.internal.enums.WorkorderStatus;
import com.positivity.workorder.internal.repository.TechnicianAssignmentRepository;
import com.positivity.workorder.internal.repository.WorkorderLaborEntryRepository;
import com.positivity.workorder.internal.repository.WorkorderRepository;
import com.positivity.workorder.service.IdempotencyService;
import com.positivity.workorder.service.WorkexecTimeTrackingService;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class WorkexecTimeTrackingServiceImpl implements WorkexecTimeTrackingService {
        private final Clock clock;

        private static final String SYSTEM_USER_ID = "system";
        private static final UUID UNSPECIFIED_WORKORDER_ITEM_ID = UUID
                        .fromString("00000000-0000-0000-0000-000000000001");

        private static final Set<WorkorderStatus> TIMER_ELIGIBLE_STATUSES = Set.of(
                        WorkorderStatus.APPROVED,
                        WorkorderStatus.ASSIGNED,
                        WorkorderStatus.WORK_IN_PROGRESS,
                        WorkorderStatus.AWAITING_PARTS,
                        WorkorderStatus.AWAITING_APPROVAL);

        private final WorkorderRepository workorderRepository;
        private final WorkorderLaborEntryRepository laborEntryRepository;
        private final TechnicianAssignmentRepository technicianAssignmentRepository;
        private final IdempotencyService idempotencyService;

        @Transactional(readOnly = true)
        @NonNull
        public List<WorkexecJobTimeTotalResponse> getJobTimeTotals(
                        @NonNull LocalDate startDate,
                        @NonNull LocalDate endDate,
                        @NonNull ZoneId timezone,
                        @Nullable UUID locationId,
                        @NonNull List<UUID> technicianIds) {

                LocalDateTime queryStartUtc = startDate.minusDays(1).atStartOfDay();
                LocalDateTime queryEndUtc = endDate.plusDays(2).atStartOfDay();
                List<WorkorderLaborEntry> entries = laborEntryRepository
                                .findFinalizedByEndTimeBetween(queryStartUtc, queryEndUtc, WorkorderStatus.COMPLETED);

                Map<String, WorkexecJobTimeTotalResponse> grouped = new LinkedHashMap<>();

                for (WorkorderLaborEntry entry : entries) {
                        UUID technicianId = entry.getTechnicianId();
                        Workorder workorder = entry.getWorkorder();
                        UUID rowLocationId = workorder.getShopId();
                        LocalDateTime entryEndTime = entry.getEndTime();
                        boolean skip = entryEndTime == null
                                        || (!technicianIds.isEmpty() && !technicianIds.contains(technicianId))
                                        || (locationId != null && (rowLocationId == null
                                                        || !locationId.equals(rowLocationId)));
                        LocalDate performedDate = entryEndTime == null
                                        ? null
                                        : entryEndTime.atOffset(ZoneOffset.UTC).atZoneSameInstant(timezone)
                                                        .toLocalDate();
                        boolean outsideRange = performedDate == null
                                        || performedDate.isBefore(startDate)
                                        || performedDate.isAfter(endDate);

                        if (skip || outsideRange) {
                                continue;
                        }

                        int minutes = entry.getHoursWorked()
                                        .multiply(BigDecimal.valueOf(60))
                                        .setScale(0, RoundingMode.HALF_UP)
                                        .intValue();

                        String key = technicianId + "|" + rowLocationId + "|" + performedDate;
                        WorkexecJobTimeTotalResponse current = grouped.get(key);
                        if (current == null) {
                                grouped.put(key, WorkexecJobTimeTotalResponse.builder()
                                                .technicianId(technicianId)
                                                .locationId(rowLocationId)
                                                .localDate(performedDate)
                                                .totalJobMinutes(minutes)
                                                .build());
                        } else {
                                current.setTotalJobMinutes(current.getTotalJobMinutes() + minutes);
                        }
                }

                List<WorkexecJobTimeTotalResponse> response = new ArrayList<>(grouped.values());
                response.sort(Comparator
                                .comparing(WorkexecJobTimeTotalResponse::getLocalDate)
                                .thenComparing(WorkexecJobTimeTotalResponse::getTechnicianId));
                return response;
        }

        @Transactional
        public WorkexecTimeTrackingService.LaborPerformedResult recordLaborPerformed(
                        @NonNull WorkexecLaborPerformedRequest request,
                        @NonNull String idempotencyKey) {

                Optional<UUID> existingId = idempotencyService.getExistingLaborEntryId(idempotencyKey);
                if (existingId.isPresent()) {
                        WorkorderLaborEntry existing = laborEntryRepository.findById(existingId.get())
                                        .orElseThrow(
                                                        () -> new NoSuchElementException(
                                                                        "Labor performed entry not found: "
                                                                                        + existingId.get()));
                        return new WorkexecTimeTrackingService.LaborPerformedResult(
                                        toLaborPerformedResponse(existing, request),
                                        true);
                }

                Workorder workorder = workorderRepository.findById(request.getWorkorderId())
                                .orElseThrow(() -> new NoSuchElementException(
                                                "Workorder not found: " + request.getWorkorderId()));

                if (isBlockedForLaborPosting(workorder)) {
                        throw new WorkexecTimeTrackingService.WorkexecConflictException(
                                        "WORKEXEC_CONFLICT_WORKORDER_STATE",
                                        "Cannot record labor for workorder in state " + workorder.getStatus());
                }

                if (!"HOURS".equalsIgnoreCase(request.getLabor().getUnit())) {
                        throw new IllegalArgumentException("labor.unit must be HOURS");
                }

                BigDecimal quantity = request.getLabor().getQuantity();
                if (quantity == null || quantity.compareTo(BigDecimal.ZERO) <= 0) {
                        throw new IllegalArgumentException("labor.quantity must be greater than 0");
                }

                LocalDateTime endTime = LocalDateTime.ofInstant(request.getPerformedAt(), ZoneOffset.UTC);
                long secondsWorked = quantity.multiply(BigDecimal.valueOf(3600))
                                .setScale(0, RoundingMode.HALF_UP)
                                .longValue();
                LocalDateTime startTime = endTime.minusSeconds(secondsWorked);

                UUID workorderItemId = UUID.nameUUIDFromBytes(
                                ("workexec-labor-performed:" + request.getSource().getSourceReferenceId())
                                                .getBytes(StandardCharsets.UTF_8));

                String notes = "sourceSystem=" + request.getSource().getSystem() +
                                ";sourceReferenceId=" + request.getSource().getSourceReferenceId();

                WorkorderLaborEntry entry = WorkorderLaborEntry.builder()
                                .workorder(workorder)
                                .workorderService(new WorkorderService(workorderItemId))
                                .technicianId(request.getTechnicianId())
                                .startTime(startTime)
                                .endTime(endTime)
                                .hoursWorked(quantity.setScale(2, RoundingMode.HALF_UP))
                                .notes(notes)
                                .createdBy(SYSTEM_USER_ID)
                                .createdAt(Instant.now(clock))
                                .build();

                WorkorderLaborEntry saved = laborEntryRepository.save(entry);
                idempotencyService.registerLaborKey(idempotencyKey, saved.getId());

                return new WorkexecTimeTrackingService.LaborPerformedResult(toLaborPerformedResponse(saved, request),
                                false);
        }

        @Transactional(readOnly = true)
        @NonNull
        public List<WorkexecTimerEntryResponse> getActiveTimers(@NonNull UUID mechanicId) {
                return laborEntryRepository.findByTechnicianIdAndEndTimeIsNullOrderByStartTimeDesc(mechanicId)
                                .stream()
                                .map(this::toTimerResponse)
                                .toList();
        }

        @Transactional
        public WorkexecTimeTrackingService.TimerStartResult startTimer(
                        @NonNull UUID mechanicId,
                        @NonNull WorkexecTimerStartRequest request,
                        @Nullable String idempotencyKey) {

                if (idempotencyKey != null && !idempotencyKey.isBlank()) {
                        Optional<UUID> existingId = idempotencyService.getExistingLaborEntryId(idempotencyKey);
                        if (existingId.isPresent()) {
                                WorkorderLaborEntry existing = laborEntryRepository.findById(existingId.get())
                                                .orElseThrow(() -> new NoSuchElementException(
                                                                "Timer entry not found: " + existingId.get()));
                                return new WorkexecTimeTrackingService.TimerStartResult(toTimerResponse(existing),
                                                true);
                        }
                }

                Workorder workorder = workorderRepository.findById(request.getWorkorderId())
                                .orElseThrow(() -> new NoSuchElementException(
                                                "Workorder not found: " + request.getWorkorderId()));

                if (!TIMER_ELIGIBLE_STATUSES.contains(workorder.getStatus())) {
                        throw new WorkexecTimeTrackingService.WorkexecConflictException(
                                        "INVALID_STATE",
                                        "Workorder is not in a timer-eligible state");
                }

                technicianAssignmentRepository.findByWorkorder_IdAndCurrentTrue(request.getWorkorderId())
                                .ifPresent(assignment -> {
                                        if (!mechanicId.equals(assignment.getTechnicianId())) {
                                                throw new WorkexecTimeTrackingService.WorkexecConflictException(
                                                                "INVALID_STATE",
                                                                "Workorder is currently assigned to a different mechanic");
                                        }
                                });

                List<WorkorderLaborEntry> active = laborEntryRepository
                                .findByTechnicianIdAndEndTimeIsNullOrderByStartTimeDesc(mechanicId);
                if (!active.isEmpty()) {
                        throw new WorkexecTimeTrackingService.WorkexecConflictException("TIMER_ALREADY_ACTIVE",
                                        "Mechanic already has an active timer");
                }

                UUID workorderItemId = request.getWorkorderItemId() != null
                                ? request.getWorkorderItemId()
                                : UNSPECIFIED_WORKORDER_ITEM_ID;

                WorkorderLaborEntry entry = WorkorderLaborEntry.builder()
                                .workorder(workorder)
                                .workorderService(new WorkorderService(workorderItemId))
                                .technicianId(mechanicId)
                                .startTime(LocalDateTime.now(ZoneOffset.UTC))
                                .hoursWorked(BigDecimal.ZERO)
                                .notes(request.getLaborCode())
                                .createdBy(SecurityContextHelper.getCurrentUsername().orElse(SYSTEM_USER_ID))
                                .createdAt(Instant.now(clock))
                                .build();

                WorkorderLaborEntry saved = laborEntryRepository.save(entry);
                if (idempotencyKey != null && !idempotencyKey.isBlank()) {
                        idempotencyService.registerLaborKey(idempotencyKey, saved.getId());
                }

                return new WorkexecTimeTrackingService.TimerStartResult(toTimerResponse(saved), false);
        }

        @Transactional
        @NonNull
        public List<WorkexecTimerEntryResponse> stopTimers(@NonNull UUID mechanicId) {
                List<WorkorderLaborEntry> active = laborEntryRepository
                                .findByTechnicianIdAndEndTimeIsNullOrderByStartTimeDesc(mechanicId);
                if (active.isEmpty()) {
                        throw new WorkexecTimeTrackingService.WorkexecConflictException("NO_ACTIVE_TIMER",
                                        "No active timer exists for mechanic");
                }

                LocalDateTime stopTime = LocalDateTime.now(ZoneOffset.UTC);
                for (WorkorderLaborEntry entry : active) {
                        entry.stop(stopTime);
                }

                List<WorkorderLaborEntry> saved = laborEntryRepository.saveAll(active);
                return saved.stream().map(this::toTimerResponse).toList();
        }

        @NonNull
        private WorkexecLaborPerformedResponse toLaborPerformedResponse(
                        @NonNull WorkorderLaborEntry entry,
                        @NonNull WorkexecLaborPerformedRequest request) {
                LocalDateTime performedAt = Objects.requireNonNull(
                                entry.getEndTime() != null ? entry.getEndTime() : entry.getStartTime(),
                                "performedAt timestamp must be present");
                return WorkexecLaborPerformedResponse.builder()
                                .laborPerformedId(entry.getId())
                                .workorderId(entry.getWorkorder().getId())
                                .technicianId(entry.getTechnicianId())
                                .performedAt(performedAt.toInstant(ZoneOffset.UTC))
                                .quantity(entry.getHoursWorked())
                                .unit(request.getLabor().getUnit())
                                .sourceSystem(request.getSource().getSystem())
                                .sourceReferenceId(request.getSource().getSourceReferenceId())
                                .build();
        }

        @NonNull
        private WorkexecTimerEntryResponse toTimerResponse(@NonNull WorkorderLaborEntry entry) {
                Long durationInSeconds = null;
                if (entry.getEndTime() != null) {
                        durationInSeconds = java.time.Duration.between(entry.getStartTime(), entry.getEndTime())
                                        .getSeconds();
                }

                UUID workorderItemId = UNSPECIFIED_WORKORDER_ITEM_ID.equals(entry.getWorkorderServiceId())
                                ? null
                                : entry.getWorkorderServiceId();

                return WorkexecTimerEntryResponse.builder()
                                .timeEntryId(entry.getId())
                                .mechanicId(entry.getTechnicianId())
                                .workorderId(entry.getWorkorder().getId())
                                .workorderItemId(workorderItemId)
                                .laborCode(entry.getNotes())
                                .startTime(entry.getStartTime())
                                .endTime(entry.getEndTime())
                                .durationInSeconds(durationInSeconds)
                                .status(entry.getEndTime() == null ? "ACTIVE" : "COMPLETED")
                                .build();
        }

        private boolean isBlockedForLaborPosting(Workorder workorder) {
                if (workorder.getStatus() == WorkorderStatus.CANCELLED) {
                        return true;
                }
                return workorder.getStatus() == WorkorderStatus.COMPLETED
                                && !Boolean.TRUE.equals(workorder.getIsReopened());
        }

}
