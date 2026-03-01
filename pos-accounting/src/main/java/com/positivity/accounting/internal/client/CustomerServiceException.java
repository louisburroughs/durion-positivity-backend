package com.positivity.accounting.internal.client;

/**
 * Exception thrown when customer service integration fails.
 */
public class CustomerServiceException extends RuntimeException {

    private final int httpStatus;

    public CustomerServiceException(String message, int httpStatus) {
        super(message);
        this.httpStatus = httpStatus;
    }

    public CustomerServiceException(String message, int httpStatus, Throwable cause) {
        super(message, cause);
        this.httpStatus = httpStatus;
    }

    public int getHttpStatus() {
        return httpStatus;
    }
}
