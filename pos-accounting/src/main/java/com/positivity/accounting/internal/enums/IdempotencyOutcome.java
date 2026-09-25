package com.positivity.accounting.internal.enums;

/**
 * Idempotency outcome recorded for a Kafka-consumed inventory posting fact
 * ({@code InventoryFactIngestionRecorder}, issue #2191/#2186 D5). Distinct from the REST
 * {@code submitEvent} idempotency mechanism (content-hash dedup via {@code IdempotencyService},
 * 24h window, rejects a replay with HTTP 409 {@code DUPLICATE_EVENT} and persists nothing) — this
 * enum covers only the fact-consumption path, where every consumed fact writes a terminal
 * {@code AccountingEvent} row and the row itself carries the outcome.
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
     * First delivery of this fact: a new {@code AccountingEvent} row was written, and — when the
     * fact posts — a new journal entry.
     */
    NEW,

    /**
     * A re-delivery of a fact already recorded, matched by its deterministic {@code
     * sourceEventId}; the earlier journal entry (if any) was reused and no new posting was made.
     */
    DUPLICATE_IGNORED;

    /** Human-readable meaning, exhaustive by construction (a new constant fails to compile here). */
    public String description() {
        return switch (this) {
            case NEW ->
                "First delivery of this fact; a new AccountingEvent row was written, "
                        + "and a new journal entry if the fact posts.";
            case DUPLICATE_IGNORED ->
                "Re-delivery matched by its deterministic sourceEventId; "
                        + "the earlier journal entry was reused and no new posting was made.";
        };
    }
}
