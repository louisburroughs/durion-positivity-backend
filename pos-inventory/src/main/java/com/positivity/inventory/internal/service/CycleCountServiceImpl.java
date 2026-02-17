package com.positivity.inventory.internal.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.positivity.inventory.internal.dto.cyclecount.CountResponse;
import com.positivity.inventory.internal.dto.cyclecount.SubmitCountRequest;
import com.positivity.inventory.internal.dto.cyclecount.SubmitRecountRequest;
import com.positivity.inventory.internal.entity.CountEntry;
import com.positivity.inventory.internal.entity.CycleCountTask;
import com.positivity.inventory.internal.entity.TaskStatus;
import com.positivity.inventory.internal.exception.InsufficientPermissionException;
import com.positivity.inventory.internal.exception.InvalidCountQuantityException;
import com.positivity.inventory.internal.exception.RecountLimitExceededException;
import com.positivity.inventory.internal.exception.TaskNotFoundException;
import com.positivity.inventory.internal.repository.CountEntryRepository;
import com.positivity.inventory.internal.repository.CycleCountTaskRepository;
import com.positivity.inventory.service.CycleCountService;

import java.util.List;
import java.util.UUID;

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

    private final CycleCountTaskRepository taskRepository;
    private final CountEntryRepository countEntryRepository;

    public CycleCountServiceImpl(
            CycleCountTaskRepository taskRepository,
            CountEntryRepository countEntryRepository) {
        this.taskRepository = taskRepository;
        this.countEntryRepository = countEntryRepository;
    }

    @Override
    public CountResponse submitCount(SubmitCountRequest request) {
        log.info("Submitting count for task: {}, auditor: {}",
                request.getTaskId(), request.getAuditorId());

        // Validate quantity
        validateQuantity(request.getActualQuantity());

        // Load task
        CycleCountTask task = getTask(request.getTaskId());

        // Verify task is in ASSIGNED status
        if (task.getStatus() != TaskStatus.ASSIGNED) {
            throw new IllegalStateException(
                    String.format("Task %s is not in ASSIGNED status. Current status: %s",
                            task.getTaskId(), task.getStatus()));
        }

        // Calculate variance
        int variance = request.getActualQuantity() - task.getExpectedQuantity();

        // Create count entry
        CountEntry countEntry = CountEntry.builder()
                .cycleCountTaskId(task.getTaskId())
                .auditorId(request.getAuditorId())
                .actualQuantity(request.getActualQuantity())
                .expectedQuantity(task.getExpectedQuantity())
                .variance(variance)
                .recountSequenceNumber(0) // Original count
                .recountOfCountEntryId(null)
                .build();

        countEntry = countEntryRepository.save(countEntry);
        log.info("Created count entry: {}, variance: {}", countEntry.getCountEntryId(), variance);

        // Update task
        task.setLatestCountEntryId(countEntry.getCountEntryId());
        task.setCountEntriesCount(1);
        task.setStatus(TaskStatus.COUNTED_PENDING_REVIEW);
        taskRepository.save(task);

        return buildCountResponse(countEntry, task, false, "Count submitted successfully");
    }

    @Override
    public CountResponse submitRecount(SubmitRecountRequest request) {
        log.info("Submitting recount for task: {}, auditor: {}, permission: {}",
                request.getTaskId(), request.getAuditorId(), request.getPermission());

        // Validate quantity
        validateQuantity(request.getActualQuantity());

        // Load task
        CycleCountTask task = getTask(request.getTaskId());

        // Check recount limit
        if (task.getCountEntriesCount() >= MAX_TOTAL_COUNTS) {
            log.warn("Recount limit exceeded for task: {}. Current: {}, Max: {}",
                    task.getTaskId(), task.getCountEntriesCount(), MAX_TOTAL_COUNTS);

            // Mark task as requiring investigation
            task.setStatus(TaskStatus.REQUIRES_INVESTIGATION);
            taskRepository.save(task);

            throw new RecountLimitExceededException(
                    task.getTaskId(),
                    task.getCountEntriesCount(),
                    MAX_TOTAL_COUNTS);
        }

        // Validate recount permission
        validateRecountPermission(request.getPermission(), request.getAuditorId(),
                task, task.getCountEntriesCount());

        // Get previous count entry
        CountEntry previousEntry = countEntryRepository.findById(task.getLatestCountEntryId())
                .orElseThrow(() -> new IllegalStateException(
                        "Previous count entry not found: " + task.getLatestCountEntryId()));

        // Calculate variance
        int variance = request.getActualQuantity() - task.getExpectedQuantity();

        // Create recount entry
        int newSequenceNumber = previousEntry.getRecountSequenceNumber() + 1;
        CountEntry recountEntry = CountEntry.builder()
                .cycleCountTaskId(task.getTaskId())
                .auditorId(request.getAuditorId())
                .actualQuantity(request.getActualQuantity())
                .expectedQuantity(task.getExpectedQuantity())
                .variance(variance)
                .recountSequenceNumber(newSequenceNumber)
                .recountOfCountEntryId(previousEntry.getCountEntryId())
                .build();

        recountEntry = countEntryRepository.save(recountEntry);
        log.info("Created recount entry: {}, sequence: {}, variance: {}",
                recountEntry.getCountEntryId(), newSequenceNumber, variance);

        // Update task
        task.setLatestCountEntryId(recountEntry.getCountEntryId());
        task.setCountEntriesCount(task.getCountEntriesCount() + 1);

        // Check if this was the last allowed recount
        boolean limitReached = task.getCountEntriesCount() >= MAX_TOTAL_COUNTS;
        if (limitReached) {
            log.info("Maximum recount limit reached for task: {}", task.getTaskId());
        }

        taskRepository.save(task);

        return buildCountResponse(recountEntry, task, limitReached,
                limitReached ? "Recount submitted. Maximum recount limit reached."
                        : "Recount submitted successfully");
    }

    @Override
    @Transactional(readOnly = true)
    public CycleCountTask getTask(UUID taskId) {
        return taskRepository.findById(taskId)
                .orElseThrow(() -> new TaskNotFoundException(taskId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<CountEntry> getCountHistory(UUID taskId) {
        return countEntryRepository.findByCycleCountTaskIdOrderByRecountSequenceNumberAsc(taskId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<CycleCountTask> getTasksByAuditor(String auditorId) {
        return taskRepository.findByAuditorId(auditorId);
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
    private void validateRecountPermission(String permission, String auditorId,
            CycleCountTask task, int currentCount) {
        if (PERMISSION_RECOUNT_SELF.equals(permission)) {
            // Auditor permission: only for first recount
            if (currentCount > 1) {
                throw new InsufficientPermissionException(
                        String.format(
                                "Auditor %s can only trigger one immediate recount. " +
                                        "Current count: %d. Manager approval required for additional recounts.",
                                auditorId, currentCount));
            }

            // Must be the original auditor
            if (!task.getAuditorId().equals(auditorId)) {
                throw new InsufficientPermissionException(
                        String.format(
                                "Auditor %s cannot recount task assigned to %s. " +
                                        "Only the original auditor or a manager can trigger recounts.",
                                auditorId, task.getAuditorId()));
            }
        } else if (PERMISSION_RECOUNT_ANY.equals(permission)) {
            // Manager permission: can trigger any recount
            log.info("Manager recount authorized for task: {}", task.getTaskId());
        } else {
            throw new InsufficientPermissionException(
                    String.format("Invalid permission: %s. Expected %s or %s",
                            permission, PERMISSION_RECOUNT_SELF, PERMISSION_RECOUNT_ANY));
        }
    }

    /**
     * Builds a count response DTO from a count entry and task.
     */
    private CountResponse buildCountResponse(CountEntry countEntry, CycleCountTask task,
            boolean limitExceeded, String message) {
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
}
