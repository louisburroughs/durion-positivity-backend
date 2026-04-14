package com.positivity.inventory.internal.service;

import com.positivity.inventory.internal.dto.cyclecount.CountEntryResponse;
import com.positivity.inventory.internal.dto.cyclecount.CountResponse;
import com.positivity.inventory.internal.dto.cyclecount.CycleCountTaskResponse;
import com.positivity.inventory.internal.dto.cyclecount.SubmitCountRequest;
import com.positivity.inventory.internal.dto.cyclecount.SubmitRecountRequest;
import com.positivity.inventory.internal.entity.CountEntry;
import com.positivity.inventory.internal.entity.CycleCountTask;
import com.positivity.inventory.internal.enums.TaskStatus;
import com.positivity.inventory.internal.exception.InsufficientPermissionException;
import com.positivity.inventory.internal.exception.InvalidCountQuantityException;
import com.positivity.inventory.internal.exception.RecountLimitExceededException;
import com.positivity.inventory.internal.exception.TaskNotFoundException;
import com.positivity.inventory.internal.repository.CountEntryRepository;
import com.positivity.inventory.internal.repository.CycleCountTaskRepository;
import com.positivity.inventory.service.CycleCountService;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implementation of cycle count service with recount support.
 *
 * <p>
 * Based on clarification for issue #27:
 * <ul>
 * <li>Maximum 3 total counts (original + 2 recounts)</li>
 * <li>Auditor can trigger 1 immediate recount (TRIGGER_RECOUNT_SELF)</li>
 * <li>Manager can trigger additional recounts (TRIGGER_RECOUNT_ANY)</li>
 * <li>Exceeding limit marks task as REQUIRES_INVESTIGATION</li>
 * </ul>
 */
@Service
@Transactional
public class CycleCountServiceImpl implements CycleCountService {

    private static final Logger log = LoggerFactory.getLogger(CycleCountServiceImpl.class);

    private static final int MAX_TOTAL_COUNTS = 3; // Original + 2 recounts
    private static final String PERMISSION_RECOUNT_SELF = "TRIGGER_RECOUNT_SELF";
    private static final String PERMISSION_RECOUNT_ANY = "TRIGGER_RECOUNT_ANY";

    private final Clock clock;
    private final CycleCountTaskRepository taskRepository;
    private final CountEntryRepository countEntryRepository;

    public CycleCountServiceImpl(
            CycleCountTaskRepository taskRepository, CountEntryRepository countEntryRepository, Clock clock) {
        this.taskRepository = taskRepository;
        this.countEntryRepository = countEntryRepository;
        this.clock = clock;
    }

    @Override
    public CountResponse submitCount(SubmitCountRequest request) {
        if (log.isInfoEnabled()) {
            log.info(
                    "Submitting count for task: {}, auditor: {}",
                    maskForLog(request.getTaskId()),
                    maskForLog(request.getAuditorId()));
        }

        // Validate quantity
        validateQuantity(request.getActualQuantity());

        // Load task
        CycleCountTask task = getTaskEntity(request.getTaskId());

        // Verify task is in ASSIGNED status
        if (task.getStatus() != TaskStatus.ASSIGNED) {
            throw new IllegalStateException(String.format(
                    "Task %s is not in ASSIGNED status. Current status: %s", task.getTaskId(), task.getStatus()));
        }

        // Calculate variance
        int variance = request.getActualQuantity() - task.getExpectedQuantity();

        // Create count entry
        CountEntry countEntry = CountEntry.builder()
                .cycleCountTask(task)
                .auditorId(request.getAuditorId())
                .actualQuantity(request.getActualQuantity())
                .expectedQuantity(task.getExpectedQuantity())
                .variance(variance)
                .recountSequenceNumber(0) // Original count
                .recountOfCountEntry(null)
                .countedAt(Instant.now(clock))
                .build();

        countEntry = countEntryRepository.save(countEntry);
        if (log.isInfoEnabled()) {
            log.info("Created count entry: {}, variance: {}", maskForLog(countEntry.getCountEntryId()), variance);
        }

        // Update task
        task.setLatestCountEntryId(countEntry.getCountEntryId());
        task.setCountEntriesCount(1);
        task.setStatus(TaskStatus.COUNTED_PENDING_REVIEW);
        taskRepository.save(task);

        return buildCountResponse(countEntry, task, false, "Count submitted successfully");
    }

    @Override
    public CountResponse submitRecount(SubmitRecountRequest request) {

        if (log.isInfoEnabled()) {
            log.info("POST inventory - cycle-count - recount request received");
        }

        // Validate quantity
        validateQuantity(request.getActualQuantity());

        // Load task
        CycleCountTask task = getTaskEntity(request.getTaskId());

        // Check recount limit
        if (task.getCountEntriesCount() >= MAX_TOTAL_COUNTS) {
            if (log.isWarnEnabled()) {
                log.warn(
                        "Recount limit exceeded for task: {}. Current: {}, Max: {}",
                        maskForLog(task.getTaskId()),
                        task.getCountEntriesCount(),
                        MAX_TOTAL_COUNTS);
            }

            // Mark task as requiring investigation
            task.setStatus(TaskStatus.REQUIRES_INVESTIGATION);
            taskRepository.save(task);

            throw new RecountLimitExceededException(task.getTaskId(), task.getCountEntriesCount(), MAX_TOTAL_COUNTS);
        }

        // Validate recount permission
        validateRecountPermission(request.getPermission(), request.getAuditorId(), task, task.getCountEntriesCount());

        // Get previous count entry
        CountEntry previousEntry = countEntryRepository
                .findById(task.getLatestCountEntryId())
                .orElseThrow(() ->
                        new IllegalStateException("Previous count entry not found: " + task.getLatestCountEntryId()));

        // Calculate variance
        int variance = request.getActualQuantity() - task.getExpectedQuantity();

        // Create recount entry
        int newSequenceNumber = previousEntry.getRecountSequenceNumber() + 1;
        CountEntry recountEntry = CountEntry.builder()
                .cycleCountTask(task)
                .auditorId(request.getAuditorId())
                .actualQuantity(request.getActualQuantity())
                .expectedQuantity(task.getExpectedQuantity())
                .variance(variance)
                .recountSequenceNumber(newSequenceNumber)
                .recountOfCountEntry(previousEntry)
                .countedAt(Instant.now(clock))
                .build();

        recountEntry = countEntryRepository.save(recountEntry);
        if (log.isInfoEnabled()) {
            log.info(
                    "Created recount entry: {}, sequence: {}, variance: {}",
                    maskForLog(recountEntry.getCountEntryId()),
                    newSequenceNumber,
                    variance);
        }

        // Update task
        task.setLatestCountEntryId(recountEntry.getCountEntryId());
        task.setCountEntriesCount(task.getCountEntriesCount() + 1);
        task.setStatus(TaskStatus.COUNTED_PENDING_REVIEW);

        // Check if this was the last allowed recount
        boolean limitReached = task.getCountEntriesCount() >= MAX_TOTAL_COUNTS;
        if (limitReached && log.isInfoEnabled()) {
            log.info("Maximum recount limit reached for task: {}", maskForLog(task.getTaskId()));
        }

        taskRepository.save(task);

        return buildCountResponse(
                recountEntry,
                task,
                limitReached,
                limitReached ? "Recount submitted. Maximum recount limit reached." : "Recount submitted successfully");
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

    private CycleCountTask getTaskEntity(UUID taskId) {
        return taskRepository.findById(taskId).orElseThrow(() -> new TaskNotFoundException(taskId));
    }

    /**
     * Validates that a count quantity is non-negative.
     */
    private void validateQuantity(Integer quantity) {
        if (quantity == null || quantity < 0) {
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
                .auditorId(task.getAuditorId())
                .status(task.getStatus())
                .latestCountEntryId(task.getLatestCountEntryId())
                .countEntriesCount(task.getCountEntriesCount())
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
                .expectedQuantity(countEntry.getExpectedQuantity())
                .variance(countEntry.getVariance())
                .recountSequenceNumber(countEntry.getRecountSequenceNumber())
                .recountOfCountEntryId(countEntry.getRecountOfCountEntryId())
                .countedAt(countEntry.getCountedAt())
                .recount(countEntry.isRecount())
                .build();
    }
}
