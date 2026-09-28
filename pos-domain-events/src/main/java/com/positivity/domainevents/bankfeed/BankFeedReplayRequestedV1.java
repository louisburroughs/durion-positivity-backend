package com.positivity.domainevents.bankfeed;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * Command: accounting asks a connector to re-emit an account's transactions from a date
 * (SPEC-manual-bank-reconciliation §2.2; ADR-0044 §4 bootstrap/backfill; phase 2). Sent on {@code
 * bankfeed.commands.v1} with {@code eventType = "bankfeed.replay.requested"}, keyed by {@link
 * #feedAccountId}. Defined in phase 1 (story S2, #2301); nothing sends it yet.
 *
 * @param requestId the caller's UUIDv7 idempotency key
 * @param feedConnectionId the connection the account belongs to
 * @param feedAccountId the feed account to replay
 * @param fromDate the first transaction date to re-emit
 * @param requestedAt when accounting asked
 * @param requestedBy the actor who asked
 */
public record BankFeedReplayRequestedV1(
        @NonNull UUID requestId,
        @NonNull UUID feedConnectionId,
        @NonNull UUID feedAccountId,
        @NonNull LocalDate fromDate,
        @NonNull Instant requestedAt,
        @NonNull String requestedBy) {

    public static final String EVENT_TYPE = "bankfeed.replay.requested";
    public static final int SCHEMA_VERSION = 1;

    public BankFeedReplayRequestedV1 {
        if (requestId == null || feedConnectionId == null || feedAccountId == null) {
            throw new IllegalArgumentException("requestId, feedConnectionId and feedAccountId must not be null");
        }
        if (fromDate == null) {
            throw new IllegalArgumentException("fromDate must not be null");
        }
        if (requestedAt == null) {
            throw new IllegalArgumentException("requestedAt must not be null");
        }
        if (requestedBy == null || requestedBy.isBlank()) {
            throw new IllegalArgumentException("requestedBy must not be blank");
        }
    }
}
