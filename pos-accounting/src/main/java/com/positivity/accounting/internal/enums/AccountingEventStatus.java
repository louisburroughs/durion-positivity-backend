package com.positivity.accounting.internal.enums;

/**
 * Accounting Event ingestion and processing status.
 *
 * @see <a href=
 *      "domains/accounting/.business-rules/BACKEND_CONTRACT_GUIDE.md">Backend
 *      Contract Guide</a>
 */
public enum AccountingEventStatus {
    /**
     * Event has been received and persisted, awaiting processing.
     */
    RECEIVED,

    /**
     * Event is currently being processed (JE generation in progress).
     */
    PROCESSING,

    /**
     * Event has been successfully processed and JE created.
     */
    PROCESSED,

    /**
     * Event processing failed (mapping not found, validation error, etc.).
     */
    FAILED,

    /**
     * Event has been suspended for manual review/resolution.
     */
    SUSPENDED,

    /**
     * Terminal: a Kafka-consumed posting fact that was deliberately not posted (for example an
     * uncosted inventory fact, {@code failureReasonCode = UNCOSTED_FACT}). Not retryable — the
     * retry scheduler and {@code retryAccountingEvent} select only {@link #FAILED} and
     * {@link #SUSPENDED} (issue #2191).
     */
    SKIPPED;

    /**
     * Human-readable meaning, published on {@code GET /v1/accounting/events/contract}
     * (issue #2207). A {@code switch} over every constant is exhaustive by construction: a new
     * constant added to this enum fails to compile here until it is given a meaning, so the
     * published contract cannot silently drift from the code.
     */
    public String meaning() {
        return switch (this) {
            case RECEIVED -> "Event has been received and persisted, awaiting processing.";
            case PROCESSING -> "Event is currently being processed (journal entry generation in progress).";
            case PROCESSED ->
                "Event has been successfully processed; a journal entry was created "
                        + "(or, for a fact that legitimately posts nothing, none was needed).";
            case FAILED ->
                "Event processing failed (mapping not found, validation error, etc.); "
                        + "retryable via the retry endpoint or the scheduled retry job.";
            case SUSPENDED ->
                "Event suspended for manual review/resolution; retryable via the "
                        + "reprocess endpoint once the underlying mapping or rule gap is fixed.";
            case SKIPPED ->
                "Terminal: a Kafka-consumed posting fact deliberately not posted (for "
                        + "example an uncosted inventory fact, failureReasonCode UNCOSTED_FACT). Not "
                        + "retryable — the retry scheduler and retryAccountingEvent select only FAILED "
                        + "and SUSPENDED (issue #2191).";
        };
    }
}
