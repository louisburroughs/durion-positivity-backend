package com.positivity.people.internal.client;

/**
 * Exception thrown when Security service integration fails. Indicates that a
 * cross-service call to the Security module encountered an error.
 *
 * Scenarios: - Security service is unavailable (circuit breaker open, timeout, connection
 * refused) - User or role not found (404) - Invalid request (400) - Server error (5xx)
 */
public class SecurityServiceException extends RuntimeException {

    private final int httpStatus;

    /**
     * Create exception with message and optional cause.
     * @param message error description
     */
    public SecurityServiceException(String message) {
        super(message);
        this.httpStatus = 500;
    }

    /**
     * Create exception with message and HTTP status code.
     * @param message error description
     * @param httpStatus HTTP status code from security service (or 503 for unavailable)
     */
    public SecurityServiceException(String message, int httpStatus) {
        super(message);
        this.httpStatus = httpStatus;
    }

    /**
     * Create exception with message, HTTP status, and underlying cause.
     * @param message error description
     * @param httpStatus HTTP status code
     * @param cause underlying exception
     */
    public SecurityServiceException(String message, int httpStatus, Throwable cause) {
        super(message, cause);
        this.httpStatus = httpStatus;
    }

    /**
     * Create exception with message and underlying cause.
     * @param message error description
     * @param cause underlying exception
     */
    public SecurityServiceException(String message, Throwable cause) {
        super(message, cause);
        this.httpStatus = 500;
    }

    /**
     * Get HTTP status code associated with this error.
     * @return HTTP status code
     */
    public int getHttpStatus() {
        return httpStatus;
    }

    /**
     * Check if error is due to service unavailability (circuit breaker, timeout, etc).
     * @return true if status is 503, 504, or 0 (connection failure)
     */
    public boolean isServiceUnavailable() {
        return httpStatus == 503 || httpStatus == 504 || httpStatus == 0;
    }

    /**
     * Check if error is due to resource not found.
     * @return true if status is 404
     */
    public boolean isNotFound() {
        return httpStatus == 404;
    }

    /**
     * Check if error is a client error (4xx).
     * @return true if status is 4xx
     */
    public boolean isClientError() {
        return httpStatus >= 400 && httpStatus < 500;
    }

    /**
     * Check if error is a server error (5xx).
     * @return true if status is 5xx
     */
    public boolean isServerError() {
        return httpStatus >= 500 && httpStatus < 600;
    }
}
