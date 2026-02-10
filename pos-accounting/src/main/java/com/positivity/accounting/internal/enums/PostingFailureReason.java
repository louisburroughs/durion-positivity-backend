package com.positivity.accounting.internal.enums;

/**
 * Reasons why posting rule evaluation can fail.
 * Used to provide actionable feedback when posting engine cannot produce a
 * journal entry.
 */
public enum PostingFailureReason {
    /**
     * No published posting rule version found for the organization and transaction
     * date.
     * Indicates missing or inactive rule configuration.
     */
    NO_RULE_VERSION,

    /**
     * Event type does not match any rule mappings (exact, fallback, or category
     * default).
     * Event will be marked SUSPENDED pending manual mapping configuration.
     */
    UNMAPPED_EVENT_TYPE,

    /**
     * Generated journal entry is not balanced (debits != credits).
     * Indicates a bug in mapping rules or calculation logic.
     */
    UNBALANCED_JOURNAL,

    /**
     * Multiple exact matches found for mapping key (ambiguous mapping).
     * Requires manual intervention to resolve mapping conflicts.
     */
    AMBIGUOUS_MAPPING,

    /**
     * Event payload failed validation (missing required fields, invalid data).
     * Event must be corrected at source or schema updated.
     */
    VALIDATION_ERROR,

    /**
     * Internal error occurred during rule evaluation.
     * Review logs and exception details for root cause.
     */
    INTERNAL_ERROR
}
