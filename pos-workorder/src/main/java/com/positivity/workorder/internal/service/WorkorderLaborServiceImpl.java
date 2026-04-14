package com.positivity.workorder.internal.service;

import com.positivity.workorder.internal.entity.Workorder;
import com.positivity.workorder.internal.entity.WorkorderLaborEntry;
import com.positivity.workorder.internal.entity.WorkorderServiceLine;
import com.positivity.workorder.internal.enums.WorkorderStatus;
import com.positivity.workorder.internal.repository.WorkorderLaborEntryRepository;
import com.positivity.workorder.internal.repository.WorkorderRepository;
import com.positivity.workorder.internal.repository.WorkorderServiceRepository;
import com.positivity.workorder.service.IdempotencyService;
import com.positivity.workorder.service.WorkorderLaborService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service for managing labor entries on workorders.
 *
 * <p>
 * Business rules:
 * <ul>
 * <li>Labor can only be started when workorder is in progress (ASSIGNED,
 * WORK_IN_PROGRESS, etc.)</li>
 * <li>Only one active labor session allowed per service at a time</li>
 * <li>Stop calculates hours from duration unless manually overridden</li>
 * <li>Supports idempotency for create operations</li>
 * </ul>
 *
 * <p>
 * Implements CAP-005 Story #159 - Record Labor Performed
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WorkorderLaborServiceImpl implements WorkorderLaborService {
    private static final String LABOR_ENTRY_NOT_FOUND = "Labor entry not found: ";
    private final Clock clock;
    private static final String IDEMPOTENCY_OPERATION_LABOR_START = "labor.start";
    private static final String IDEMPOTENCY_OPERATION_LABOR_STOP = "labor.stop";
    private static final String IDEMPOTENCY_OPERATION_LABOR_ADJUST = "labor.adjust";

    private final WorkorderLaborEntryRepository laborRepository;
    private final WorkorderRepository workorderRepository;
    private final WorkorderServiceRepository workorderServiceRepository;
    private final IdempotencyService idempotencyService;

    private static final Set<WorkorderStatus> LABOR_ALLOWED_STATUSES = Set.of(
            WorkorderStatus.ASSIGNED,
            WorkorderStatus.WORK_IN_PROGRESS,
            WorkorderStatus.AWAITING_PARTS,
            WorkorderStatus.AWAITING_APPROVAL);

    /**
     * Start a labor session on a workorder service.
     *
     * @param workorderId    the workorder ID
     * @param serviceId      the service line item ID
     * @param technicianId   the technician performing the work
     * @param notes          optional session notes
     * @param createdBy      the user starting the session
     * @param idempotencyKey optional idempotency key
     * @return the created labor entry
     * @throws NoSuchElementException if workorder not found
     * @throws IllegalStateException  if workorder status doesn't allow labor or
     *                                active session exists
     */
    @Transactional
    @NonNull
    public WorkorderLaborEntry startLaborSession(
            @NonNull UUID workorderId,
            @NonNull UUID serviceId,
            @NonNull UUID technicianId,
            @Nullable String notes,
            @NonNull String createdBy,
            @Nullable String idempotencyKey) {

        // Check idempotency first
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            Optional<UUID> existingId =
                    idempotencyService.getExistingLaborEntryId(IDEMPOTENCY_OPERATION_LABOR_START, idempotencyKey);
            if (existingId.isPresent()) {
                log.info(
                        "Idempotency key {} already processed, returning existing labor entry {}",
                        idempotencyKey,
                        existingId.get());
                return laborRepository
                        .findById(existingId.get())
                        .orElseThrow(() -> new NoSuchElementException(LABOR_ENTRY_NOT_FOUND + existingId.get()));
            }
        }

        // Validate workorder exists and status allows labor
        Workorder workorder = workorderRepository
                .findById(workorderId)
                .orElseThrow(() -> new NoSuchElementException("Workorder not found: " + workorderId));

        if (!LABOR_ALLOWED_STATUSES.contains(workorder.getStatus())) {
            throw new IllegalStateException("Cannot start labor on workorder with status " + workorder.getStatus()
                    + ". Must be in ASSIGNED, WORK_IN_PROGRESS, AWAITING_PARTS, or AWAITING_APPROVAL.");
        }

        // Check for existing active session on this service
        Optional<WorkorderLaborEntry> activeSession =
                laborRepository.findByWorkorderService_IdAndEndTimeIsNull(serviceId);
        if (activeSession.isPresent()) {
            throw new IllegalStateException("Active labor session already exists for service " + serviceId);
        }

        WorkorderServiceLine workorderService = workorderServiceRepository
                .findById(serviceId)
                .orElseThrow(() -> new NoSuchElementException("Workorder service not found: " + serviceId));
        if (workorderService.getWorkOrder() == null
                || !workorderId.equals(workorderService.getWorkOrder().getId())) {
            throw new IllegalStateException("Service " + serviceId + " does not belong to workorder " + workorderId);
        }

        // Create new labor entry
        WorkorderLaborEntry entry = WorkorderLaborEntry.builder()
                .workorder(workorder)
                .workorderService(workorderService)
                .technicianId(technicianId)
                .startTime(LocalDateTime.now(clock))
                .hoursWorked(BigDecimal.ZERO)
                .notes(notes)
                .createdBy(createdBy)
                .createdAt(Instant.now(clock))
                .build();

        WorkorderLaborEntry saved = laborRepository.save(entry);
        log.info("Started labor session {} for service {} by technician {}", saved.getId(), serviceId, technicianId);

        // Register idempotency key if provided
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            idempotencyService.registerLaborKey(IDEMPOTENCY_OPERATION_LABOR_START, idempotencyKey, saved.getId());
        }

        return saved;
    }

    /**
     * Stop an active labor session.
     *
     * @param entryId        the labor entry ID
     * @param idempotencyKey optional idempotency key
     * @return the updated labor entry
     * @throws NoSuchElementException if entry not found
     * @throws IllegalStateException  if session already stopped
     */
    @Transactional
    @NonNull
    public WorkorderLaborEntry stopLaborSession(@NonNull UUID entryId, @Nullable String idempotencyKey) {

        // Check idempotency first
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            Optional<UUID> existingId =
                    idempotencyService.getExistingLaborEntryId(IDEMPOTENCY_OPERATION_LABOR_STOP, idempotencyKey);
            if (existingId.isPresent()) {
                log.info(
                        "Idempotency key {} already processed, returning existing stopped labor entry {}",
                        idempotencyKey,
                        existingId.get());
                return laborRepository
                        .findById(existingId.get())
                        .orElseThrow(() -> new NoSuchElementException(LABOR_ENTRY_NOT_FOUND + existingId.get()));
            }
        }

        WorkorderLaborEntry entry = laborRepository
                .findById(entryId)
                .orElseThrow(() -> new NoSuchElementException(LABOR_ENTRY_NOT_FOUND + entryId));

        if (!entry.isActive()) {
            throw new IllegalStateException("Labor session already stopped");
        }

        entry.stop(LocalDateTime.now(clock));
        WorkorderLaborEntry saved = laborRepository.save(entry);
        log.info("Stopped labor session {} - {} hours worked", entryId, saved.getHoursWorked());

        // Register idempotency key if provided
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            idempotencyService.registerLaborKey(IDEMPOTENCY_OPERATION_LABOR_STOP, idempotencyKey, saved.getId());
        }

        return saved;
    }

    /**
     * Get all labor history for a workorder.
     *
     * @param workorderId the workorder ID
     * @return list of labor entries, newest first
     */
    @Transactional(readOnly = true)
    @NonNull
    public List<WorkorderLaborEntry> getLaborHistory(@NonNull UUID workorderId) {
        return laborRepository.findByWorkorder_IdOrderByStartTimeDesc(workorderId);
    }

    /**
     * Manually adjust labor hours with a reason.
     *
     * @param entryId        the labor entry ID
     * @param hours          the new hours value
     * @param reason         the reason for adjustment
     * @param idempotencyKey optional idempotency key
     * @return the updated labor entry
     * @throws NoSuchElementException if entry not found
     */
    @Transactional
    @NonNull
    public WorkorderLaborEntry adjustLaborHours(
            @NonNull UUID entryId,
            @NonNull BigDecimal hours,
            @NonNull String reason,
            @NonNull String adjustedBy,
            @Nullable String idempotencyKey) {

        // Check idempotency first
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            Optional<UUID> existingId =
                    idempotencyService.getExistingLaborEntryId(IDEMPOTENCY_OPERATION_LABOR_ADJUST, idempotencyKey);
            if (existingId.isPresent()) {
                log.info(
                        "Idempotency key {} already processed, returning existing adjusted labor entry {}",
                        idempotencyKey,
                        existingId.get());
                return laborRepository
                        .findById(existingId.get())
                        .orElseThrow(() -> new NoSuchElementException(LABOR_ENTRY_NOT_FOUND + existingId.get()));
            }
        }

        WorkorderLaborEntry entry = laborRepository
                .findById(entryId)
                .orElseThrow(() -> new NoSuchElementException(LABOR_ENTRY_NOT_FOUND + entryId));

        entry.adjustHours(hours, reason);
        WorkorderLaborEntry saved = laborRepository.save(entry);
        log.info("Adjusted labor entry {} to {} hours - reason: {}", entryId, hours, reason);

        // Register idempotency key if provided
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            idempotencyService.registerLaborKey(IDEMPOTENCY_OPERATION_LABOR_ADJUST, idempotencyKey, saved.getId());
        }

        return saved;
    }
}
