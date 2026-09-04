package com.positivity.accounting.internal.exception;

/**
 * Thrown when a posting rule set/version cannot be published or archived:
 * the rules definition is blank or not valid JSON, no DRAFT version exists
 * to publish, or no PUBLISHED version exists to archive. Maps to HTTP 400
 * (VALIDATION_ERROR), matching the documented contract on
 * {@code POST /v1/accounting/posting-rules/{id}/publish} and {@code /archive}.
 */
public class PostingRulePublishValidationException extends RuntimeException {

    public PostingRulePublishValidationException(String message) {
        super(message);
    }

    public PostingRulePublishValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
