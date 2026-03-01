package com.positivity.accounting.internal.client;

/**
 * Exception thrown when workorder service integration fails.
 */
public class WorkorderServiceException extends RuntimeException {

    private final int httpStatus;

    public WorkorderServiceException(String message, int httpStatus) {
        super(message);
        this.httpStatus = httpStatus;
    }

    public WorkorderServiceException(String message, int httpStatus, Throwable cause) {
        super(message, cause);
        this.httpStatus = httpStatus;
    }

    public int getHttpStatus() {
        return httpStatus;
    }
}
