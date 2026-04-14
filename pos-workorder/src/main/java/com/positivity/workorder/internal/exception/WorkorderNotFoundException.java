package com.positivity.workorder.internal.exception;

import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Exception thrown when a workorder cannot be found.
 */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class WorkorderNotFoundException extends RuntimeException {

    public WorkorderNotFoundException(UUID workorderId) {
        super("Workorder not found: " + workorderId);
    }
}
