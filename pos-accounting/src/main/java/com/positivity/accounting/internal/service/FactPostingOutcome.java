package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.enums.PostingFailureReason;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * What a Kafka-consumed fact did to the ledger, as a posting path reports it back to its listener,
 * which hands it to {@link KafkaFactIngestionRecorder#record} for the fact's one {@code
 * accounting_event} row (issue #2433).
 */
public sealed interface FactPostingOutcome {

    /** A journal entry was posted for this fact: {@code PROCESSED / NEW}, linked to the entry. */
    record Posted(@NonNull UUID journalEntryId) implements FactPostingOutcome {}

    /**
     * The posting path's own idempotency key was already registered (a redelivery under a fresh
     * Kafka event id, or a later fact for an already-posted document): {@code PROCESSED /
     * DUPLICATE_IGNORED}. Linked to {@code journalEntryId} when the path knows it, else to the entry
     * found by {@code sourceEventId}, else to none (a path that never posts a journal entry).
     */
    record AlreadyPosted(
            @Nullable UUID journalEntryId, @Nullable UUID sourceEventId) implements FactPostingOutcome {}

    /** A new fact that legitimately posts nothing (a zero amount, a record-only fact): {@code PROCESSED / NEW}. */
    record NothingToPost() implements FactPostingOutcome {}

    /** A fact deliberately not posted: terminal {@code SKIPPED} with a reason and a readable detail. */
    record Skipped(
            @NonNull PostingFailureReason reason, @NonNull String detail) implements FactPostingOutcome {}

    /**
     * The posting path already wrote the fact's record itself as a currency hold ({@link
     * KafkaFactIngestionRecorder#recordCurrencyHeld}); the listener records nothing more.
     */
    record CurrencyHeld() implements FactPostingOutcome {}

    /**
     * The fact cannot post yet and is held {@code SUSPENDED} with {@code reason} (CAP:550 S32d: {@code
     * TAX_TYPE_MISSING}); the recorder writes the held record, which the audited reprocess releases through the
     * fact's own path.
     */
    record Held(
            @NonNull PostingFailureReason reason, @NonNull String detail) implements FactPostingOutcome {}

    static @NonNull FactPostingOutcome posted(@NonNull UUID journalEntryId) {
        return new Posted(journalEntryId);
    }

    static @NonNull FactPostingOutcome nothingToPost() {
        return new NothingToPost();
    }

    static @NonNull FactPostingOutcome notPostable(@NonNull String detail) {
        return new Skipped(PostingFailureReason.NOT_POSTABLE, detail);
    }

    /**
     * The one outcome of a fact two posting paths handled in the same transaction (CAP:550 S17, #2513: a closed
     * register session posts its over/short and each drawer movement), for the fact's one record. A currency hold
     * wins (the path wrote the held record itself); then a new entry ({@code first}'s before {@code second}'s), so
     * a fact that posted anything new is {@code NEW}; then an earlier posting, recorded as {@code
     * DUPLICATE_IGNORED}; then a skip; else nothing to post.
     */
    static @NonNull FactPostingOutcome combine(@NonNull FactPostingOutcome first, @NonNull FactPostingOutcome second) {
        if (first instanceof CurrencyHeld || second instanceof CurrencyHeld) {
            return new CurrencyHeld();
        }
        if (first instanceof Held) {
            return first;
        }
        if (second instanceof Held) {
            return second;
        }
        for (Class<? extends FactPostingOutcome> precedence :
                List.of(Posted.class, AlreadyPosted.class, Skipped.class)) {
            if (precedence.isInstance(first)) {
                return first;
            }
            if (precedence.isInstance(second)) {
                return second;
            }
        }
        return nothingToPost();
    }
}
