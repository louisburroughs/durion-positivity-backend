package com.positivity.domainevents.order;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Fact: a register session was opened on a terminal (#2573; Order Domain ruling for S38, #2571).
 *
 * <p>Published by pos-order on {@code order.events.v1} with {@code eventType = "order.session.opened"} when a
 * session is opened (S40, #2578). A terminal has at most one active session per tenant: a session is active (OPEN
 * or CLOSING in pos-order) from this fact until its {@code order.session.closed} fact ({@link
 * RegisterSessionClosedV1}), and its {@code locationId} never changes while it is active. The envelope's aggregate
 * is the session ({@code aggregateId = sessionId}), and its version orders the two facts of one session.
 *
 * <p>Consumers: pos-accounting keeps a replica of the sessions per terminal and refuses to relocate a register
 * (its {@code registerId} is the {@code terminalId}, AW31) while its latest-opened session is open.
 *
 * @param sessionId register session identifier (also the envelope aggregateId)
 * @param terminalId terminal the session runs on
 * @param locationId shop location, when set on the session; unchanged for the session's life
 * @param openedAt when the session opened
 */
public record RegisterSessionOpenedV1(
        @NonNull UUID sessionId,
        @NonNull String terminalId,
        @Nullable UUID locationId,
        @NonNull Instant openedAt) {

    public static final String EVENT_TYPE = "order.session.opened";
    public static final int SCHEMA_VERSION = 1;

    public RegisterSessionOpenedV1 {
        if (sessionId == null || openedAt == null) {
            throw new IllegalArgumentException("sessionId and openedAt must not be null");
        }
        if (terminalId == null || terminalId.isBlank()) {
            throw new IllegalArgumentException("terminalId must not be blank");
        }
    }
}
