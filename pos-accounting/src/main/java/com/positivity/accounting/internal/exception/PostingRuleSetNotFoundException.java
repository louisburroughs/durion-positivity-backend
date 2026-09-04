package com.positivity.accounting.internal.exception;

/**
 * Thrown when a posting rule set is not found by ID. Maps to HTTP 404
 * (POSTING_RULE_SET_NOT_FOUND) — ADR-0017 §1's default for a missing addressed resource
 * (issue #1694 review finding).
 */
public class PostingRuleSetNotFoundException extends RuntimeException {

    public PostingRuleSetNotFoundException(String message) {
        super(message);
    }
}
