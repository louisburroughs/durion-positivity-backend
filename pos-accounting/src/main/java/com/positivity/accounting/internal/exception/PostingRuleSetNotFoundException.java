package com.positivity.accounting.internal.exception;

/**
 * Thrown when a posting rule set is not found by ID. Maps to HTTP 400
 * (POSTING_RULE_SET_NOT_FOUND) — deliberately, not 404;
 * {@code PostingRuleController}'s endpoint descriptions state this
 * explicitly ("mapped as VALIDATION_ERROR, not 404").
 */
public class PostingRuleSetNotFoundException extends RuntimeException {

    public PostingRuleSetNotFoundException(String message) {
        super(message);
    }
}
