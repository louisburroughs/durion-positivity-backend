package com.positivity.workorder.internal.service;

import com.positivity.security.common.SecurityContextHelper;
import com.positivity.workorder.internal.entity.TechnicianAssignment;
import com.positivity.workorder.internal.entity.Workorder;
import com.positivity.workorder.internal.entity.WorkorderLaborEntry;
import com.positivity.workorder.internal.entity.WorkorderServiceLine;
import com.positivity.workorder.internal.enums.WorkorderStatus;
import com.positivity.workorder.internal.repository.TechnicianAssignmentRepository;
import com.positivity.workorder.internal.repository.WorkorderLaborEntryRepository;
import com.positivity.workorder.internal.repository.WorkorderRepository;
import com.positivity.workorder.internal.repository.WorkorderServiceRepository;
import com.positivity.workorder.service.IdempotencyService;
import com.positivity.workorder.service.WorkexecTimeTrackingService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
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
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class WorkexecTimeTrackingServiceImpl implements WorkexecTimeTrackingService {
    private final Clock clock;

    private static final String SYSTEM_USER_ID = "system";
    private static final String PERMISSION_LABOR_ADD_ON_BEHALF = "workorder:labor:add_on_behalf";
    private static final String IDEMPOTENCY_OPERATION_WORKEXEC_LABOR_PERFORMED = "workexec.labor.performed";
    private static final String IDEMPOTENCY_OPERATION_WORKEXEC_TIMER_START = "workexec.timer.start";

    private static final Set<WorkorderStatus> TIMER_ELIGIBLE_STATUSES = Set.of(
            WorkorderStatus.APPROVED,
            WorkorderStatus.ASSIGNED,
            WorkorderStatus.WORK_IN_PROGRESS,
            WorkorderStatus.AWAITING_PARTS,
            WorkorderStatus.AWAITING_APPROVAL);

    private final WorkorderRepository workorderRepository;
    private final WorkorderLaborEntryRepository laborEntryRepository;
    private final WorkorderServiceRepository workorderServiceRepository;
    private final TechnicianAssignmentRepository technicianAssignmentRepository;
    private final IdempotencyService idempotencyService;

    @Transactional(readOnly = true)
    @NonNull
    public List<WorkexecTimeTrackingService.JobTimeTotal> getJobTimeTotals(
            @NonNull LocalDate startDate,
            @NonNull LocalDate endDate,
            @NonNull ZoneId timezone,
            @Nullable UUID locationId,
            @NonNull List<UUID> technicianIds) {

        LocalDateTime queryStartUtc = startDate.minusDays(1).atStartOfDay();
        LocalDateTime queryEndUtc = endDate.plusDays(2).atStartOfDay();
        List<WorkorderLaborEntry> entries = laborEntryRepository.findFinalizedByEndTimeBetween(
                queryStartUtc, queryEndUtc, WorkorderStatus.COMPLETED);

        Map<String, WorkexecTimeTrackingService.JobTimeTotal> grouped = new LinkedHashMap<>();

        for (WorkorderLaborEntry entry : entries) {
            UUID technicianId = entry.getTechnicianId();
            Workorder workorder = entry.getWorkorder();
            UUID rowLocationId = workorder.getShopId();
            LocalDateTime entryEndTime = entry.getEndTime();
            boolean skip = entryEndTime == null
                    || (!technicianIds.isEmpty() && !technicianIds.contains(technicianId))
                    || (locationId != null && (rowLocationId == null || !locationId.equals(rowLocationId)));
            LocalDate performedDate = entryEndTime == null
                    ? null
                    : entryEndTime
                            .atOffset(ZoneOffset.UTC)
                            .atZoneSameInstant(timezone)
                            .toLocalDate();
            boolean outsideRange =
                    performedDate == null || performedDate.isBefore(startDate) || performedDate.isAfter(endDate);

            if (skip || outsideRange) {
                continue;
            }

            int minutes = entry.getHoursWorked()
                    .multiply(BigDecimal.valueOf(60))
                    .setScale(0, RoundingMode.HALF_UP)
                    .intValue();

            String key = technicianId + "|" + rowLocationId + "|" + performedDate;
            WorkexecTimeTrackingService.JobTimeTotal current = grouped.get(key);
            if (current == null) {
                grouped.put(
                        key,
                        new WorkexecTimeTrackingService.JobTimeTotal(
                                technicianId, rowLocationId, performedDate, minutes));
            } else {
                grouped.put(
                        key,
                        new WorkexecTimeTrackingService.JobTimeTotal(
                                current.technicianId(),
                                current.locationId(),
                                current.localDate(),
                                current.totalJobMinutes() + minutes));
            }
        }

        List<WorkexecTimeTrackingService.JobTimeTotal> response = new ArrayList<>(grouped.values());
        response.sort(Comparator.comparing(WorkexecTimeTrackingService.JobTimeTotal::localDate)
                .thenComparing(WorkexecTimeTrackingService.JobTimeTotal::technicianId));
        return response;
    }

    @Transactional
    public WorkexecTimeTrackingService.LaborPerformedResult recordLaborPerformed(
            WorkexecTimeTrackingService.LaborPerformedRequest request, @NonNull String idempotencyKey) {

        Optional<UUID> existingId = idempotencyService.getExistingLaborEntryId(
                IDEMPOTENCY_OPERATION_WORKEXEC_LABOR_PERFORMED, idempotencyKey);
        if (existingId.isPresent()) {
            WorkorderLaborEntry existing = laborEntryRepository
                    .findById(existingId.get())
                    .orElseThrow(
                            () -> new NoSuchElementException("Labor performed entry not found: " + existingId.get()));
            return new WorkexecTimeTrackingService.LaborPerformedResult(
                    toLaborPerformedResponse(existing, request), true);
        }

        Workorder workorder = workorderRepository
                .findById(request.workorderId())
                .orElseThrow(() -> new NoSuchElementException("Workorder not found: " + request.workorderId()));

        if (isBlockedForLaborPosting(workorder)) {
            throw new WorkexecTimeTrackingService.WorkexecConflictException(
                    "WORKEXEC_CONFLICT_WORKORDER_STATE",
                    "Cannot record labor for workorder in state " + workorder.getStatus());
        }

        if (!"HOURS".equalsIgnoreCase(request.labor().unit())) {
            throw new IllegalArgumentException("labor.unit must be HOURS");
        }

        BigDecimal quantity = request.labor().quantity();
        if (quantity == null || quantity.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("labor.quantity must be greater than 0");
        }

        LocalDateTime endTime = LocalDateTime.ofInstant(request.performedAt(), ZoneOffset.UTC);
        long secondsWorked = quantity.multiply(BigDecimal.valueOf(3600))
                .setScale(0, RoundingMode.HALF_UP)
                .longValue();
        LocalDateTime startTime = endTime.minusSeconds(secondsWorked);

        WorkorderServiceLine workorderService =
                resolveOrCreateWorkorderService(workorder, null, request.technicianId());

        String notes = "sourceSystem=" + request.source().system() + ";sourceReferenceId="
                + request.source().sourceReferenceId();

        WorkorderLaborEntry entry = WorkorderLaborEntry.builder()
                .workorder(workorder)
                .workorderService(workorderService)
                .technicianId(request.technicianId())
                .startTime(startTime)
                .endTime(endTime)
                .hoursWorked(quantity.setScale(2, RoundingMode.HALF_UP))
                .notes(notes)
                .createdBy(SYSTEM_USER_ID)
                .createdAt(Instant.now(clock))
                .build();

        WorkorderLaborEntry saved = laborEntryRepository.save(entry);
        idempotencyService.registerLaborKey(
                IDEMPOTENCY_OPERATION_WORKEXEC_LABOR_PERFORMED, idempotencyKey, saved.getId());

        return new WorkexecTimeTrackingService.LaborPerformedResult(toLaborPerformedResponse(saved, request), false);
    }

    @Transactional(readOnly = true)
    @NonNull
    public List<WorkexecTimeTrackingService.TimerEntry> getActiveTimers(@NonNull UUID mechanicId) {
        // Mirror stopTimers: surface timers the actor tracks or initiated, so the active list and the
        // stop operation agree for default-attribution / on-behalf entries.
        return laborEntryRepository.findActiveByTechnicianOrActor(mechanicId).stream()
                .map(this::toTimerResponse)
                .toList();
    }

    @Transactional
    public WorkexecTimeTrackingService.TimerStartResult startTimer(
            @NonNull UUID mechanicId,
            WorkexecTimeTrackingService.TimerStartRequest request,
            @Nullable String idempotencyKey) {

        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            Optional<UUID> existingId = idempotencyService.getExistingLaborEntryId(
                    IDEMPOTENCY_OPERATION_WORKEXEC_TIMER_START, idempotencyKey);
            if (existingId.isPresent()) {
                WorkorderLaborEntry existing = laborEntryRepository
                        .findById(existingId.get())
                        .orElseThrow(() -> new NoSuchElementException("Timer entry not found: " + existingId.get()));
                return new WorkexecTimeTrackingService.TimerStartResult(toTimerResponse(existing), true);
            }
        }

        Workorder workorder = workorderRepository
                .findById(request.workorderId())
                .orElseThrow(() -> new NoSuchElementException("Workorder not found: " + request.workorderId()));

        if (!TIMER_ELIGIBLE_STATUSES.contains(workorder.getStatus())) {
            throw new WorkexecTimeTrackingService.WorkexecConflictException(
                    "INVALID_STATE", "Workorder is not in a timer-eligible state");
        }

        // The authenticated initiator. May differ from the technician whose labor is tracked.
        UUID actorPersonId = mechanicId;

        // Current assignment is the default attribution target (shopmgmt system of record), not the actor.
        Optional<UUID> assignedTechnicianOpt = technicianAssignmentRepository
                .findByWorkorder_IdAndCurrentTrue(request.workorderId())
                .map(TechnicianAssignment::getTechnicianId);
        UUID assignedTechnicianId = assignedTechnicianOpt.isPresent() ? assignedTechnicianOpt.get() : null;

        UUID trackedTechnicianId = resolveTrackedTechnician(request, actorPersonId, assignedTechnicianId);
        boolean onBehalf = authorizeAttribution(request, actorPersonId, assignedTechnicianId, trackedTechnicianId);

        // Uniqueness is a property of whose labor is tracked, not who initiated the timer.
        List<WorkorderLaborEntry> active =
                laborEntryRepository.findByTechnicianIdAndEndTimeIsNullOrderByStartTimeDesc(trackedTechnicianId);
        if (!active.isEmpty()) {
            throw new WorkexecTimeTrackingService.WorkexecConflictException(
                    "TIMER_ALREADY_ACTIVE", "Technician already has an active timer");
        }

        WorkorderServiceLine workorderService =
                resolveOrCreateWorkorderService(workorder, request.workorderItemId(), trackedTechnicianId);

        WorkorderLaborEntry entry = WorkorderLaborEntry.builder()
                .workorder(workorder)
                .workorderService(workorderService)
                .technicianId(trackedTechnicianId)
                .startTime(LocalDateTime.now(ZoneOffset.UTC))
                .hoursWorked(BigDecimal.ZERO)
                .notes(request.laborCode())
                .createdBy(SecurityContextHelper.getCurrentUsername().orElse(SYSTEM_USER_ID))
                .onBehalfReason(onBehalf ? request.reason() : null)
                // Record the initiating actor whenever the tracked technician differs from the actor
                // (default-attribution and on-behalf paths). This is what lets the initiator stop the
                // timer they started: stop/active resolve by tracked technician OR initiating actor.
                .actedByUserId(trackedTechnicianId.equals(actorPersonId) ? null : actorPersonId)
                .createdAt(Instant.now(clock))
                .build();

        WorkorderLaborEntry saved = laborEntryRepository.save(entry);
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            idempotencyService.registerLaborKey(
                    IDEMPOTENCY_OPERATION_WORKEXEC_TIMER_START, idempotencyKey, saved.getId());
        }

        return new WorkexecTimeTrackingService.TimerStartResult(toTimerResponse(saved), false);
    }

    /** Resolve whose labor the timer tracks: explicit override, else current assignment, else self. */
    private static UUID resolveTrackedTechnician(
            WorkexecTimeTrackingService.TimerStartRequest request,
            UUID actorPersonId,
            @Nullable UUID assignedTechnicianId) {
        if (request.technicianId() != null) {
            return request.technicianId();
        }
        return assignedTechnicianId != null ? assignedTechnicianId : actorPersonId;
    }

    /**
     * Authorize the labor attribution and report whether it is an on-behalf action.
     *
     * <p>The base authority ({@code workorder:labor:add}) covers attributing labor to yourself or to the
     * workorder's current assignee — the latter is the intended default-attribution path, so a non-assignee
     * may start a timer for the assigned technician without elevation. Attributing labor to any other
     * technician requires {@code workorder:labor:add_on_behalf} plus a non-blank reason, and is recorded as
     * an audited on-behalf entry.
     *
     * @return {@code true} when this is an on-behalf attribution (tracked technician differs from both the
     *     actor and the current assignment); {@code false} for the base path.
     */
    private static boolean authorizeAttribution(
            WorkexecTimeTrackingService.TimerStartRequest request,
            UUID actorPersonId,
            @Nullable UUID assignedTechnicianId,
            UUID trackedTechnicianId) {
        boolean baseAllowed =
                trackedTechnicianId.equals(actorPersonId) || trackedTechnicianId.equals(assignedTechnicianId);
        if (baseAllowed) {
            return false;
        }
        if (!SecurityContextHelper.hasAuthority(PERMISSION_LABOR_ADD_ON_BEHALF)) {
            throw new AccessDeniedException(
                    "Starting a timer for another technician requires '" + PERMISSION_LABOR_ADD_ON_BEHALF + "'");
        }
        if (request.reason() == null || request.reason().isBlank()) {
            throw new IllegalArgumentException(
                    "reason is required when starting a timer on behalf of another technician");
        }
        return true;
    }

    @Transactional
    @NonNull
    public List<WorkexecTimeTrackingService.TimerEntry> stopTimers(@NonNull UUID mechanicId) {
        // Resolve by tracked technician OR initiating actor so a timer started via the
        // default-attribution path (tracked technician != actor) can be stopped by its initiator.
        List<WorkorderLaborEntry> active = laborEntryRepository.findActiveByTechnicianOrActor(mechanicId);
        if (active.isEmpty()) {
            throw new WorkexecTimeTrackingService.WorkexecConflictException(
                    "NO_ACTIVE_TIMER", "No active timer exists for mechanic");
        }

        LocalDateTime stopTime = LocalDateTime.now(ZoneOffset.UTC);
        for (WorkorderLaborEntry entry : active) {
            entry.stop(stopTime);
        }

        List<WorkorderLaborEntry> saved = laborEntryRepository.saveAll(active);
        return saved.stream().map(this::toTimerResponse).toList();
    }

    private WorkexecTimeTrackingService.LaborPerformedResponse toLaborPerformedResponse(
            @NonNull WorkorderLaborEntry entry, WorkexecTimeTrackingService.LaborPerformedRequest request) {
        LocalDateTime performedAt = Objects.requireNonNull(
                entry.getEndTime() != null ? entry.getEndTime() : entry.getStartTime(),
                "performedAt timestamp must be present");
        return new WorkexecTimeTrackingService.LaborPerformedResponse(
                entry.getId(),
                entry.getWorkorder().getId(),
                entry.getTechnicianId(),
                performedAt.toInstant(ZoneOffset.UTC),
                entry.getHoursWorked(),
                request.labor().unit(),
                request.source().system(),
                request.source().sourceReferenceId());
    }

    private WorkexecTimeTrackingService.TimerEntry toTimerResponse(@NonNull WorkorderLaborEntry entry) {
        Long durationInSeconds = null;
        if (entry.getEndTime() != null) {
            // Labor timestamps are stored in UTC (see startTimer/stopTimers). Compute the duration on
            // zone-aware values so the result is DST-safe rather than on bare LocalDateTime.
            durationInSeconds = java.time.Duration.between(
                            entry.getStartTime().atOffset(ZoneOffset.UTC),
                            entry.getEndTime().atOffset(ZoneOffset.UTC))
                    .getSeconds();
        }

        return new WorkexecTimeTrackingService.TimerEntry(
                entry.getId(),
                entry.getTechnicianId(),
                entry.getWorkorder().getId(),
                entry.getWorkorderServiceId(),
                entry.getNotes(),
                entry.getStartTime(),
                entry.getEndTime(),
                durationInSeconds,
                entry.getEndTime() == null ? "ACTIVE" : "COMPLETED");
    }

    @NonNull
    private WorkorderServiceLine resolveOrCreateWorkorderService(
            @NonNull Workorder workorder, @Nullable UUID requestedWorkorderItemId, @Nullable UUID technicianId) {
        if (requestedWorkorderItemId != null) {
            WorkorderServiceLine service = workorderServiceRepository
                    .findById(requestedWorkorderItemId)
                    .orElseThrow(() ->
                            new NoSuchElementException("Workorder service not found: " + requestedWorkorderItemId));
            if (service.getWorkOrder() != null
                    && !workorder.getId().equals(service.getWorkOrder().getId())) {
                throw new WorkexecTimeTrackingService.WorkexecConflictException(
                        "INVALID_STATE", "workorderItemId does not belong to the provided workorder");
            }
            return service;
        }

        return workorderServiceRepository.findByWorkOrder_Id(workorder.getId()).stream()
                .findFirst()
                .orElseGet(() -> workorderServiceRepository.save(WorkorderServiceLine.builder()
                        .workOrder(workorder)
                        .serviceEntityId(UUID.nameUUIDFromBytes(
                                ("workexec-auto-service:" + workorder.getId()).getBytes(StandardCharsets.UTF_8)))
                        .technicianId(technicianId)
                        .description("Workexec auto-created labor service")
                        .build()));
    }

    private boolean isBlockedForLaborPosting(Workorder workorder) {
        if (workorder.getStatus() == WorkorderStatus.CANCELLED) {
            return true;
        }
        return workorder.getStatus() == WorkorderStatus.COMPLETED && !Boolean.TRUE.equals(workorder.getIsReopened());
    }
}
