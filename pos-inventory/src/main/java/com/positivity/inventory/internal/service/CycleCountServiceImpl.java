package com.positivity.inventory.internal.service;

import com.positivity.inventory.internal.dto.cyclecount.CountEntryResponse;
import com.positivity.inventory.internal.dto.cyclecount.CountResponse;
import com.positivity.inventory.internal.dto.cyclecount.CycleCountTaskResponse;
import com.positivity.inventory.internal.dto.cyclecount.InterferingMovementResponse;
import com.positivity.inventory.internal.dto.cyclecount.SubmitCountRequest;
import com.positivity.inventory.internal.dto.cyclecount.SubmitRecountRequest;
import com.positivity.inventory.internal.entity.CountEntry;
import com.positivity.inventory.internal.entity.CycleCountTask;
import com.positivity.inventory.internal.entity.InventoryLedgerEntry;
import com.positivity.inventory.internal.enums.MeasurementMethod;
import com.positivity.inventory.internal.enums.TaskStatus;
import com.positivity.inventory.internal.exception.InsufficientPermissionException;
import com.positivity.inventory.internal.exception.InvalidCountQuantityException;
import com.positivity.inventory.internal.exception.RecountLimitExceededException;
import com.positivity.inventory.internal.exception.TaskNotFoundException;
import com.positivity.inventory.internal.exception.UomConversionUndefinedException;
import com.positivity.inventory.internal.repository.CountEntryRepository;
import com.positivity.inventory.internal.repository.CycleCountTaskRepository;
import com.positivity.inventory.service.CycleCountService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implementation of cycle count service with recount support and tolerance-based reconciliation
 * (ADR-0055 stage 4, #1416; issue #1414 owner spec).
 *
 * <p>
 * Based on clarification for issue #27:
 * <ul>
 * <li>Maximum 3 total counts (original + 2 recounts)</li>
 * <li>Auditor can trigger 1 immediate recount (TRIGGER_RECOUNT_SELF)</li>
 * <li>Manager can trigger additional recounts (TRIGGER_RECOUNT_ANY)</li>
 * <li>Exceeding limit marks task as REQUIRES_INVESTIGATION</li>
 * </ul>
 *
 * <p>
 * Since #1416, every submission also resolves a tolerance for the task's product/location and
 * compares the variance to it: within tolerance, the count is reconciled and the task closes
 * {@code ACCEPTED_WITHIN_TOLERANCE} with no {@code CycleCountAdjustment} ever created; exceeding
 * tolerance, the task follows its pre-existing path ({@code COUNTED_PENDING_REVIEW} or
 * {@code CONFLICT}) unchanged, so a reviewer decides whether to create an adjustment through the
 * separate {@code CycleCountAdjustmentService} — the count and the adjustment remain separate
 * transactions, per the owner's spec. A movement conflict always takes precedence: a task whose
 * snapshot is already known stale is never auto-accepted.
 */
@Service
@Transactional
public class CycleCountServiceImpl implements CycleCountService {

    private static final Logger log = LoggerFactory.getLogger(CycleCountServiceImpl.class);

    private static final int MAX_TOTAL_COUNTS = 3; // Original + 2 recounts
    private static final String PERMISSION_RECOUNT_SELF = "TRIGGER_RECOUNT_SELF";
    private static final String PERMISSION_RECOUNT_ANY = "TRIGGER_RECOUNT_ANY";
    private static final int PERCENTAGE_SCALE = 4;

    private final Clock clock;
    private final CycleCountTaskRepository taskRepository;
    private final CountEntryRepository countEntryRepository;
    private final CycleCountConflictDetector conflictDetector;
    private final CycleCountToleranceResolver toleranceResolver;
    private final UomConversionService uomConversionService;
    private final QuantityScaleGuard scaleGuard;
    private final BaseUnitOfMeasureResolver baseUnitOfMeasureResolver;

    public CycleCountServiceImpl(
            CycleCountTaskRepository taskRepository,
            CountEntryRepository countEntryRepository,
            Clock clock,
            CycleCountConflictDetector conflictDetector,
            CycleCountToleranceResolver toleranceResolver,
            UomConversionService uomConversionService,
            QuantityScaleGuard scaleGuard,
            BaseUnitOfMeasureResolver baseUnitOfMeasureResolver) {
        this.taskRepository = taskRepository;
        this.countEntryRepository = countEntryRepository;
        this.clock = clock;
        this.conflictDetector = conflictDetector;
        this.toleranceResolver = toleranceResolver;
        this.uomConversionService = uomConversionService;
        this.scaleGuard = scaleGuard;
        this.baseUnitOfMeasureResolver = baseUnitOfMeasureResolver;
    }

    @Override
    public CountResponse submitCount(SubmitCountRequest request) {
        if (log.isInfoEnabled()) {
            log.info(
                    "Submitting count for task: {}, auditor: {}",
                    maskForLog(request.getTaskId()),
                    maskForLog(request.getAuditorId()));
        }

        validateQuantity(request.getActualQuantity());

        CycleCountTask task = getTaskEntity(request.getTaskId());

        if (task.getStatus() != TaskStatus.ASSIGNED) {
            throw new IllegalStateException(String.format(
                    "Task %s is not in ASSIGNED status. Current status: %s", task.getTaskId(), task.getStatus()));
        }

        CountEntry countEntry = countEntryRepository.save(buildCountEntry(
                task,
                request.getAuditorId(),
                request.getActualQuantity(),
                request.getUnitOfMeasure(),
                request.getMeasurementMethod(),
                request.getVarianceReason(),
                0,
                null));
        if (log.isInfoEnabled()) {
            log.info(
                    "Created count entry: {}, variance: {}, withinTolerance: {}",
                    maskForLog(countEntry.getCountEntryId()),
                    countEntry.getVariance(),
                    countEntry.isWithinTolerance());
        }

        task.setLatestCountEntryId(countEntry.getCountEntryId());
        task.setCountEntriesCount(1);
        boolean conflict = applyConflictStatus(task);
        reconcileWithinToleranceForConflict(countEntry, conflict);
        acceptIfWithinTolerance(task, countEntry, conflict);
        taskRepository.save(task);

        return buildCountResponse(countEntry, task, false, outcomeMessage(conflict, countEntry.isWithinTolerance()));
    }

    @Override
    public CountResponse submitRecount(SubmitRecountRequest request) {

        if (log.isInfoEnabled()) {
            log.info("POST inventory - cycle-count - recount request received");
        }

        validateQuantity(request.getActualQuantity());

        CycleCountTask task = getTaskEntity(request.getTaskId());

        if (task.getCountEntriesCount() >= MAX_TOTAL_COUNTS) {
            if (log.isWarnEnabled()) {
                log.warn(
                        "Recount limit exceeded for task: {}. Current: {}, Max: {}",
                        maskForLog(task.getTaskId()),
                        task.getCountEntriesCount(),
                        MAX_TOTAL_COUNTS);
            }

            task.setStatus(TaskStatus.REQUIRES_INVESTIGATION);
            taskRepository.save(task);

            throw new RecountLimitExceededException(task.getTaskId(), task.getCountEntriesCount(), MAX_TOTAL_COUNTS);
        }

        validateRecountPermission(request.getPermission(), request.getAuditorId(), task, task.getCountEntriesCount());

        CountEntry previousEntry = countEntryRepository
                .findById(task.getLatestCountEntryId())
                .orElseThrow(() ->
                        new IllegalStateException("Previous count entry not found: " + task.getLatestCountEntryId()));

        int newSequenceNumber = previousEntry.getRecountSequenceNumber() + 1;
        CountEntry recountEntry = countEntryRepository.save(buildCountEntry(
                task,
                request.getAuditorId(),
                request.getActualQuantity(),
                request.getUnitOfMeasure(),
                request.getMeasurementMethod(),
                request.getVarianceReason(),
                newSequenceNumber,
                previousEntry));
        if (log.isInfoEnabled()) {
            log.info(
                    "Created recount entry: {}, sequence: {}, variance: {}",
                    maskForLog(recountEntry.getCountEntryId()),
                    newSequenceNumber,
                    recountEntry.getVariance());
        }

        task.setLatestCountEntryId(recountEntry.getCountEntryId());
        task.setCountEntriesCount(task.getCountEntriesCount() + 1);
        boolean conflict = applyConflictStatus(task);
        reconcileWithinToleranceForConflict(recountEntry, conflict);
        acceptIfWithinTolerance(task, recountEntry, conflict);

        boolean limitReached = task.getCountEntriesCount() >= MAX_TOTAL_COUNTS;
        if (limitReached && log.isInfoEnabled()) {
            log.info("Maximum recount limit reached for task: {}", maskForLog(task.getTaskId()));
        }

        taskRepository.save(task);

        return buildCountResponse(
                recountEntry,
                task,
                limitReached,
                limitReached
                        ? "Recount submitted. Maximum recount limit reached."
                        : outcomeMessage(conflict, recountEntry.isWithinTolerance()));
    }

    @Override
    @Transactional(readOnly = true)
    public CycleCountTaskResponse getTask(UUID taskId) {
        return toTaskResponse(getTaskEntity(taskId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<CountEntryResponse> getCountHistory(UUID taskId) {
        if (log.isInfoEnabled()) {
            log.info("GET /v1/inventory/cycle-count/task/{}/history", maskForLog(taskId));
        }
        return countEntryRepository.findByCycleCountTask_TaskIdOrderByRecountSequenceNumberAsc(taskId).stream()
                .map(this::toCountEntryResponse)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<CycleCountTaskResponse> getTasksByAuditor(String auditorId) {
        if (log.isInfoEnabled()) {
            log.info("GET /v1/inventory/cycle-count/auditor/{}/tasks", maskForLog(auditorId));
        }
        return taskRepository.findByAuditorId(auditorId).stream()
                .map(this::toTaskResponse)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<InterferingMovementResponse> getInterferingMovements(UUID taskId) {
        CycleCountTask task = getTaskEntity(taskId);
        return conflictDetector.interferingMovements(task).stream()
                .map(this::toInterferingMovementResponse)
                .toList();
    }

    private CycleCountTask getTaskEntity(UUID taskId) {
        return taskRepository.findById(taskId).orElseThrow(() -> new TaskNotFoundException(taskId));
    }

    /**
     * Builds and persists-ready the count entry: resolves the product, converts the measured
     * quantity to base UoM, validates it against the product's declared scale, computes the
     * variance and its percentage, resolves the applicable tolerance, and decides
     * {@code withinTolerance}.
     */
    private CountEntry buildCountEntry(
            CycleCountTask task,
            String auditorId,
            BigDecimal measuredQuantity,
            @Nullable String requestedUom,
            @Nullable MeasurementMethod measurementMethod,
            @Nullable String varianceReason,
            int recountSequenceNumber,
            @Nullable CountEntry recountOf) {
        UUID productId = QuantityScaleGuard.productIdOf(task.getItemSku());
        String uomCode = normalizeUom(requestedUom);
        BigDecimal actualQuantity = toBaseQuantity(productId, task.getItemSku(), uomCode, measuredQuantity);
        actualQuantity = scaleGuard.requirePostable(productId, task.getItemSku(), "actualQuantity", actualQuantity);

        BigDecimal expected = Quantities.nz(task.getExpectedQuantity());
        BigDecimal variance = actualQuantity.subtract(expected);
        BigDecimal variancePercentage = variancePercentage(variance, expected);

        CycleCountToleranceResolver.Resolution resolution = toleranceResolver.resolve(productId, task.getBinLocation());
        boolean withinTolerance = toleranceResolver.isWithinTolerance(resolution, variance.abs(), expected);

        return CountEntry.builder()
                .cycleCountTask(task)
                .auditorId(auditorId)
                .actualQuantity(actualQuantity)
                .measuredQuantity(measuredQuantity)
                .uomCode(uomCode)
                .measurementMethod(measurementMethod != null ? measurementMethod : MeasurementMethod.MANUAL_COUNT)
                .expectedQuantity(expected)
                .variance(variance)
                .variancePercentage(variancePercentage)
                .allowedToleranceAbsolute(resolution.absoluteTolerance())
                .allowedTolerancePercentage(resolution.percentageTolerance())
                .withinTolerance(withinTolerance)
                .varianceReason(varianceReason)
                .recountSequenceNumber(recountSequenceNumber)
                .recountOfCountEntry(recountOf)
                .countedAt(Instant.now(clock))
                .build();
    }

    /**
     * Converts a measured quantity to base UoM. {@code null}/blank {@code uomCode} means the
     * count was already keyed in base UoM — the identity conversion, no product lookup required.
     * {@code HALF_UP}: a count is a measurement of reality, not a reservation promise that must
     * never overstate (contrast the {@code DOWN} rounding {@link UomConversionService} uses for
     * reservations).
     */
    private BigDecimal toBaseQuantity(
            @Nullable UUID productId, String stockReference, @Nullable String uomCode, BigDecimal quantity) {
        if (uomCode == null) {
            return quantity;
        }
        if (productId == null) {
            throw UomConversionUndefinedException.unresolvableProduct(stockReference, uomCode);
        }
        return uomConversionService.toBaseQuantity(productId, uomCode, quantity);
    }

    private @Nullable String normalizeUom(@Nullable String uomCode) {
        return (uomCode == null || uomCode.isBlank()) ? null : uomCode.trim();
    }

    /** {@code |variance| / |book| × 100}; {@code 100} when book quantity is zero. */
    private BigDecimal variancePercentage(BigDecimal variance, BigDecimal book) {
        if (book.signum() == 0) {
            return BigDecimal.valueOf(100);
        }
        return variance.abs()
                .divide(book.abs(), PERCENTAGE_SCALE, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
    }

    /**
     * Sets the task status from conflict detection (odoo-parity I2, #1026):
     * CONFLICT when the count window has a non-zero net movement delta,
     * COUNTED_PENDING_REVIEW otherwise. Returns whether a conflict was found.
     */
    private boolean applyConflictStatus(CycleCountTask task) {
        BigDecimal movementDelta = conflictDetector.movementDeltaSinceSnapshot(task);
        if (!Quantities.isZero(movementDelta)) {
            if (log.isWarnEnabled()) {
                log.warn(
                        "Cycle count conflict detected at submission for task {}: in-window movement delta {}",
                        maskForLog(task.getTaskId()),
                        movementDelta);
            }
            task.setStatus(TaskStatus.CONFLICT);
            return true;
        }
        task.setStatus(TaskStatus.COUNTED_PENDING_REVIEW);
        return false;
    }

    /**
     * Overrides the just-applied status to {@code ACCEPTED_WITHIN_TOLERANCE} when the count's
     * variance is within tolerance and no movement conflict was detected. A conflicted task is
     * never auto-accepted — its expected-quantity snapshot is already known stale, so the
     * tolerance comparison itself was made against a number that may no longer be honest; the
     * existing recount-or-recompute path (odoo-parity I2) settles that before tolerance applies.
     */
    private void acceptIfWithinTolerance(CycleCountTask task, CountEntry countEntry, boolean conflict) {
        if (!conflict && countEntry.isWithinTolerance()) {
            task.setStatus(TaskStatus.ACCEPTED_WITHIN_TOLERANCE);
        }
    }

    /**
     * Forces the entry's {@code withinTolerance} to {@code false} once a movement conflict is
     * detected, even when the raw variance-vs-tolerance comparison passed. A conflict means the
     * expected-quantity snapshot that comparison ran against is already known stale, so nothing
     * was actually reconciled — the field must not go on claiming otherwise, matching what
     * {@code CountEntryResponse}/{@code CountResponse} document: "true: reconciled, no adjustment
     * created".
     */
    private void reconcileWithinToleranceForConflict(CountEntry countEntry, boolean conflict) {
        if (conflict && countEntry.isWithinTolerance()) {
            countEntry.setWithinTolerance(false);
        }
    }

    private String outcomeMessage(boolean conflict, boolean withinTolerance) {
        if (conflict) {
            return "Count submitted, but stock moved during the count window; task flagged CONFLICT —"
                    + " request a recount or approve with the variance recomputed against current on-hand";
        }
        if (withinTolerance) {
            return "Count accepted — variance within tolerance; no adjustment required";
        }
        return "Count submitted; variance exceeds tolerance and is flagged for investigation — awaiting review"
                + " before an adjustment may be created";
    }

    private InterferingMovementResponse toInterferingMovementResponse(InventoryLedgerEntry entry) {
        return InterferingMovementResponse.builder()
                .ledgerEntryId(entry.getLedgerEntryId())
                .eventType(entry.getEventType())
                .changeInQuantity(entry.getChangeInQuantity())
                .quantityAfter(entry.getQuantityAfter())
                .locationId(entry.getLocationId())
                .timestamp(entry.getTimestamp())
                .transactionUserId(entry.getTransactionUserId())
                .notes(entry.getNotes())
                .build();
    }

    /**
     * Validates that a count quantity is non-negative. Divisibility (whether a fraction is
     * allowed at all) is a separate, per-product concern enforced downstream by
     * {@link QuantityScaleGuard} once the quantity has been converted to base UoM.
     */
    private void validateQuantity(BigDecimal quantity) {
        if (quantity == null || quantity.signum() < 0) {
            throw new InvalidCountQuantityException(quantity);
        }
    }

    /**
     * Validates recount permission based on clarification rules:
     * - TRIGGER_RECOUNT_SELF: Auditor can trigger ONE immediate recount (sequence 1
     * only)
     * - TRIGGER_RECOUNT_ANY: Manager can trigger additional recounts
     */
    private void validateRecountPermission(String permission, String auditorId, CycleCountTask task, int currentCount) {
        if (PERMISSION_RECOUNT_SELF.equals(permission)) {
            // Auditor permission: only for first recount
            if (currentCount > 1) {
                throw new InsufficientPermissionException(String.format(
                        "Auditor %s can only trigger one immediate recount. "
                                + "Current count: %d. Manager approval required for additional recounts.",
                        auditorId, currentCount));
            }

            // Must be the original auditor
            if (!task.getAuditorId().equals(auditorId)) {
                throw new InsufficientPermissionException(String.format(
                        "Auditor %s cannot recount task assigned to %s. "
                                + "Only the original auditor or a manager can trigger recounts.",
                        auditorId, task.getAuditorId()));
            }
        } else if (PERMISSION_RECOUNT_ANY.equals(permission)) {
            // Manager permission: can trigger any recount
            if (log.isInfoEnabled()) {
                log.info("Manager recount authorized for task: {}", maskForLog(task.getTaskId()));
            }
        } else {
            throw new InsufficientPermissionException(String.format(
                    "Invalid permission: %s. Expected %s or %s",
                    permission, PERMISSION_RECOUNT_SELF, PERMISSION_RECOUNT_ANY));
        }
    }

    private String maskForLog(Object value) {
        if (value == null) {
            return "null";
        }
        String sanitized =
                value.toString().replace('\r', '_').replace('\n', '_').replace('\t', '_');
        int length = sanitized.length();
        if (length <= 4) {
            return "****";
        }
        return sanitized.substring(0, 2) + "***" + sanitized.substring(length - 2);
    }

    /**
     * Builds a count response DTO from a count entry and task.
     */
    private CountResponse buildCountResponse(
            CountEntry countEntry, CycleCountTask task, boolean limitExceeded, String message) {
        return CountResponse.builder()
                .countEntryId(countEntry.getCountEntryId())
                .taskId(task.getTaskId())
                .actualQuantity(countEntry.getActualQuantity())
                .expectedQuantity(countEntry.getExpectedQuantity())
                .variance(countEntry.getVariance())
                .withinTolerance(countEntry.isWithinTolerance())
                .recountSequenceNumber(countEntry.getRecountSequenceNumber())
                .taskStatus(task.getStatus())
                .countedAt(countEntry.getCountedAt())
                .limitExceeded(limitExceeded)
                .message(message)
                .build();
    }

    private CycleCountTaskResponse toTaskResponse(CycleCountTask task) {
        return CycleCountTaskResponse.builder()
                .taskId(task.getTaskId())
                .binLocation(task.getBinLocation())
                .itemSku(task.getItemSku())
                .itemDescription(task.getItemDescription())
                .expectedQuantity(task.getExpectedQuantity())
                .unitOfMeasure(baseUnitOfMeasureResolver.resolve(task.getItemSku()))
                .auditorId(task.getAuditorId())
                .status(task.getStatus())
                .latestCountEntryId(task.getLatestCountEntryId())
                .countEntriesCount(task.getCountEntriesCount())
                .createdAt(
                        task.getCreatedAt() != null
                                ? LocalDateTime.ofInstant(task.getCreatedAt(), ZoneOffset.UTC)
                                : null)
                .updatedAt(
                        task.getUpdatedAt() != null
                                ? LocalDateTime.ofInstant(task.getUpdatedAt(), ZoneOffset.UTC)
                                : null)
                .build();
    }

    private CountEntryResponse toCountEntryResponse(CountEntry countEntry) {
        return CountEntryResponse.builder()
                .countEntryId(countEntry.getCountEntryId())
                .cycleCountTaskId(
                        countEntry.getCycleCountTask() == null
                                ? null
                                : countEntry.getCycleCountTask().getTaskId())
                .auditorId(countEntry.getAuditorId())
                .actualQuantity(countEntry.getActualQuantity())
                .measuredQuantity(countEntry.getMeasuredQuantity())
                .unitOfMeasure(countEntry.getUomCode())
                .measurementMethod(countEntry.getMeasurementMethod())
                .expectedQuantity(countEntry.getExpectedQuantity())
                .variance(countEntry.getVariance())
                .variancePercentage(countEntry.getVariancePercentage())
                .allowedToleranceAbsolute(countEntry.getAllowedToleranceAbsolute())
                .allowedTolerancePercentage(countEntry.getAllowedTolerancePercentage())
                .withinTolerance(countEntry.isWithinTolerance())
                .varianceReason(countEntry.getVarianceReason())
                .recountSequenceNumber(countEntry.getRecountSequenceNumber())
                .recountOfCountEntryId(countEntry.getRecountOfCountEntryId())
                .countedAt(countEntry.getCountedAt())
                .recount(countEntry.isRecount())
                .build();
    }
}
