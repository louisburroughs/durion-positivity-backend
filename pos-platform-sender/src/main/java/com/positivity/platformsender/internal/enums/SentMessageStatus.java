package com.positivity.platformsender.internal.enums;

/**
 * Lifecycle of one send request.
 *
 * <ul>
 *   <li>{@link #PENDING}: the idempotency key is claimed and the provider call is in flight (or the
 *       process died during it; such a row answers every replay as transient, so the caller's
 *       bounded retry ends it rather than risking a second delivery).
 *   <li>{@link #ACCEPTED}: the provider took the message; replays answer with the same ids.
 *   <li>{@link #REJECTED}: refused for good (no address, provider refusal); replays answer with the
 *       same refusal.
 * </ul>
 */
public enum SentMessageStatus {
    PENDING,
    ACCEPTED,
    REJECTED
}
