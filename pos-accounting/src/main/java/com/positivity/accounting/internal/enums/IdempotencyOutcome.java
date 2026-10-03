package com.positivity.accounting.internal.enums;

/**
 * Idempotency outcome recorded for a Kafka-consumed posting fact
 * ({@code KafkaFactIngestionRecorder}, issues #2191, #2186 D5, #2433). Distinct from the REST
 * {@code submitEvent} idempotency mechanism (content-hash dedup via {@code IdempotencyService},
 * 24h window, rejects a replay with HTTP 409 {@code DUPLICATE_EVENT} and persists nothing) — this
 * enum covers only the fact-consumption path, where every consumed fact writes one
 * {@code AccountingEvent} row (terminal, except a SUSPENDED currency hold) and the row itself carries the outcome.
 *
 * <p>The persisted {@code accounting_event.idempotency_outcome} column stays a plain {@code
 * String} (not {@code @Enumerated}); callers write {@code name()} and read it back as text.
 *
 * @see <a href=
 *      "domains/accounting/.business-rules/BACKEND_CONTRACT_GUIDE.md">Backend
 *      Contract Guide</a>
 */
public enum IdempotencyOutcome {
    /**
     * The fact was not matched to an earlier one on its posting key: a new {@code AccountingEvent}
     * row was written, and — when the fact posts — a new journal entry.
     */
    NEW,

    /**
     * A re-emitted fact (new envelope {@code eventId}) matched on its listener's posting key — the
     * deterministic {@code sourceEventId} of a journal-entry fact, or the vendor + bill number of a
     * supplier invoice — so nothing new was posted; a journal-entry fact's row references the earlier
     * entry. A redelivered envelope (same {@code eventId}) never gets here: {@code processed_events}
     * short-circuits it and no row is written.
     */
    DUPLICATE_IGNORED;

    /** Human-readable meaning, exhaustive by construction (a new constant fails to compile here). */
    public String description() {
        return switch (this) {
            case NEW ->
                "Not matched to an earlier fact on its posting key; a new AccountingEvent row was written, "
                        + "and a new journal entry if the fact posts.";
            case DUPLICATE_IGNORED ->
                "Re-emitted fact (new envelope eventId) matched on its listener's posting key "
                        + "(deterministic sourceEventId for a journal-entry fact, vendor + bill number for a "
                        + "supplier invoice); nothing new was posted, and a journal-entry fact's row references "
                        + "the earlier entry. A redelivered envelope (same eventId) writes no row at all.";
        };
    }

    /** Short label for list filters, exhaustive by construction. */
    public String displayName() {
        return switch (this) {
            case NEW -> "New";
            case DUPLICATE_IGNORED -> "Duplicate ignored";
        };
    }
}
