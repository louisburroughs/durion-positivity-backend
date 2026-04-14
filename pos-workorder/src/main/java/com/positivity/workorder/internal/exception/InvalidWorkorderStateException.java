package com.positivity.workorder.internal.exception;

import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Exception thrown when a workorder state is invalid for the requested action.
 */
@ResponseStatus(HttpStatus.CONFLICT)
public class InvalidWorkorderStateException extends RuntimeException {

    public InvalidWorkorderStateException(UUID workorderId, String actualState, String expectedState) {
        super("Workorder " + workorderId + " is in state " + actualState + " and must be in " + expectedState
                + " to generate invoice");
    }
}
