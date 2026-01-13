package com.positivity.inventory.exception;

import java.util.UUID;

/**
 * Exception thrown when a cycle count task is not found.
 */
public class TaskNotFoundException extends RuntimeException {
    
    public TaskNotFoundException(UUID taskId) {
        super(String.format("Cycle count task not found: %s", taskId));
    }
}
