package com.positivity.customer.internal.enums;

/**
 * Lifecycle status for a {@link com.positivity.customer.internal.entity.ProcessingLog} entry.
 *
 * <p>Represents the final outcome of processing a single inbound workorder event.</p>
 */
public enum ProcessingStatus {

    /** Event is currently being processed. */
    PROCESSING,

    /** Event was successfully applied to the CRM data. */
    SUCCESS,

    /** Event was a duplicate (same eventId already processed); skipped. */
    SKIPPED_DUPLICATE,

    /** Event payload failed schema validation; routed to DLQ. */
    SCHEMA_VALIDATION_FAILED,

    /** Event payload was valid but violated a business rule (e.g. entity not found); routed to DLQ. */
    BUSINESS_RULE_VIOLATION
}
