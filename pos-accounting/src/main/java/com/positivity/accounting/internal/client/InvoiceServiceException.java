package com.positivity.accounting.internal.client;

/**
 * Exception thrown when Invoice service integration fails.
 * Indicates that a cross-service call to the Invoice module encountered an
 * error.
 *
 * **Scenarios:**
 * - Invoice service is unavailable (circuit breaker open, timeout, connection
 * refused)
 * - Invoice not found (404)
 * - Invoice not in applicable state (already paid, voided, cancelled)
 * - Currency mismatch
 * - Validation error (invalid amount, etc.)
 *
 * @see <a href=
 *      "https://github.com/louisburroughs/durion-positivity-backend/issues/114">Issue
 *      #114</a>
 */
public class InvoiceServiceException extends RuntimeException {

    private final int httpStatus;

    /**
     * Create exception with message and optional cause.
     *
     * @param message error description
     */
    public InvoiceServiceException(String message) {
        super(message);
        this.httpStatus = 500;
    }

    /**
     * Create exception with message and HTTP status code.
     *
     * @param message    error description
     * @param httpStatus HTTP status code from invoice service (or 503 for
     *                   unavailable)
     */
    public InvoiceServiceException(String message, int httpStatus) {
        super(message);
        this.httpStatus = httpStatus;
    }

    /**
     * Create exception with message, HTTP status, and underlying cause.
     *
     * @param message    error description
     * @param httpStatus HTTP status code
     * @param cause      underlying exception
     */
    public InvoiceServiceException(String message, int httpStatus, Throwable cause) {
        super(message, cause);
        this.httpStatus = httpStatus;
    }

    /**
     * Create exception with message and underlying cause.
     *
     * @param message error description
     * @param cause   underlying exception
     */
    public InvoiceServiceException(String message, Throwable cause) {
        super(message, cause);
        this.httpStatus = 500;
    }

    /**
     * Get HTTP status code associated with this error.
     *
     * @return HTTP status code
     */
    public int getHttpStatus() {
        return httpStatus;
    }

    /**
     * Check if error is due to service unavailability (circuit breaker, timeout,
     * etc).
     *
     * @return true if status is 503 or 504
     */
    public boolean isServiceUnavailable() {
        return httpStatus == 503 || httpStatus == 504 || httpStatus == 0;
    }

    /**
     * Check if error is due to invoice not found.
     *
     * @return true if status is 404
     */
    public boolean isNotFound() {
        return httpStatus == 404;
    }

    /**
     * Check if error is a client error (4xx).
     *
     * @return true if status is 4xx
     */
    public boolean isClientError() {
        return httpStatus >= 400 && httpStatus < 500;
    }

    /**
     * Check if error is a server error (5xx).
     *
     * @return true if status is 5xx
     */
    public boolean isServerError() {
        return httpStatus >= 500 && httpStatus < 600;
    }
}
