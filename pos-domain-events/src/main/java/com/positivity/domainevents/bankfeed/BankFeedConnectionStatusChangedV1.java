package com.positivity.domainevents.bankfeed;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Fact: a feed connection changed state (SPEC-manual-bank-reconciliation §2.2; phase 2). Published
 * by a connector on {@code bankfeed.events.v1} with {@code eventType =
 * "bankfeed.connection.status-changed"}, keyed by {@link #feedConnectionId}. The reason is a neutral
 * code, never a provider's error string. Defined in phase 1 (story S2, #2301); nothing consumes it
 * yet.
 *
 * @param feedConnectionId the connector's connection aggregate
 * @param connectorCode provenance label; never behavioural
 * @param status the new connection state
 * @param reasonCode a provider-neutral reason, when the connector gives one
 * @param changedAt when the state changed
 */
public record BankFeedConnectionStatusChangedV1(
        @NonNull UUID feedConnectionId,
        @NonNull String connectorCode,
        @NonNull ConnectionStatus status,
        @Nullable String reasonCode,
        @NonNull Instant changedAt) {

    public static final String EVENT_TYPE = "bankfeed.connection.status-changed";
    public static final int SCHEMA_VERSION = 1;

    public BankFeedConnectionStatusChangedV1 {
        if (feedConnectionId == null) {
            throw new IllegalArgumentException("feedConnectionId must not be null");
        }
        if (connectorCode == null || connectorCode.isBlank()) {
            throw new IllegalArgumentException("connectorCode must not be blank");
        }
        if (status == null) {
            throw new IllegalArgumentException("status must not be null");
        }
        if (changedAt == null) {
            throw new IllegalArgumentException("changedAt must not be null");
        }
    }

    /** Connection states (§2.2). */
    public enum ConnectionStatus {
        ACTIVE,
        LOGIN_REQUIRED,
        PENDING_DISCONNECT,
        REVOKED,
        REMOVED,
        ERROR
    }
}
