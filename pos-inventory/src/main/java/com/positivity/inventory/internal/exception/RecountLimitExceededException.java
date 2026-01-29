package com.positivity.inventory.internal.exception;

import java.util.UUID;

/**
 * Exception thrown when attempting to perform a recount beyond the maximum allowed limit.
 * 
 * <p>Per clarification for issue #27, maximum 2 recounts are allowed (3 total counts).
 */
public class RecountLimitExceededException extends RuntimeException {
    
    private final UUID taskId;
    private final int currentCount;
    private final int maxAllowed;
    
    public RecountLimitExceededException(UUID taskId, int currentCount, int maxAllowed) {
        super(String.format(
            "Recount limit exceeded for task %s. Current count: %d, Maximum allowed: %d. " +
            "Task requires investigation with manager approval.",
            taskId, currentCount, maxAllowed
        ));
        this.taskId = taskId;
        this.currentCount = currentCount;
        this.maxAllowed = maxAllowed;
    }
    
    public UUID getTaskId() {
        return taskId;
    }
    
    public int getCurrentCount() {
        return currentCount;
    }
    
    public int getMaxAllowed() {
        return maxAllowed;
    }
}
