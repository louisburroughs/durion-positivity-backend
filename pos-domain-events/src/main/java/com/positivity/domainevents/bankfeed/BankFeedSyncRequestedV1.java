package com.positivity.domainevents.bankfeed;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * Command: accounting asks a connector to refresh a connection now (SPEC-manual-bank-reconciliation
 * §2.2; phase 2). Sent on {@code bankfeed.commands.v1} with {@code eventType =
 * "bankfeed.sync.requested"}, keyed by {@link #feedConnectionId}; {@link #requestId} is the
 * idempotency key and the pending request the caller tracks (ADR-0044 R4). Defined in phase 1
 * (story S2, #2301); nothing sends it yet.
 *
 * @param requestId the caller's UUIDv7 idempotency key
 * @param feedConnectionId the connection to refresh
 * @param requestedAt when accounting asked
 * @param requestedBy the actor who asked
 */
public record BankFeedSyncRequestedV1(
        @NonNull UUID requestId,
        @NonNull UUID feedConnectionId,
        @NonNull Instant requestedAt,
        @NonNull String requestedBy) {

    public static final String EVENT_TYPE = "bankfeed.sync.requested";
    public static final int SCHEMA_VERSION = 1;

    public BankFeedSyncRequestedV1 {
        if (requestId == null || feedConnectionId == null) {
            throw new IllegalArgumentException("requestId and feedConnectionId must not be null");
        }
        if (requestedAt == null) {
            throw new IllegalArgumentException("requestedAt must not be null");
        }
        if (requestedBy == null || requestedBy.isBlank()) {
            throw new IllegalArgumentException("requestedBy must not be blank");
        }
    }
}
