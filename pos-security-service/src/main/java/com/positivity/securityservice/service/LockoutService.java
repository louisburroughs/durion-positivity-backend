package com.positivity.securityservice.service;

import org.jspecify.annotations.NonNull;

import java.util.UUID;

/**
 * Public service interface for authentication lockout bookkeeping.
 *
 * <p>Manages failed-attempt counters, progressive backoff windows, automatic
 * cooldown unlock, and administrator-triggered unlock. All state is persisted
 * to the {@code users} table via existing account-state columns introduced in
 * AUTH-002.
 *
 * <p>Applies lockout policy defined in {@code pos.security.lockout.*} properties.
 *
 * @since AUTH-003
 */
public interface LockoutService {

    /**
     * Records a failed authentication attempt for the given user and applies
     * lockout if the failure threshold within the configured time window has
     * been exceeded.
     *
     * @param userId the user's UUID identifier
     */
    void recordFailedAttempt(@NonNull UUID userId);

    /**
     * Records a successful authentication and resets the failed-attempt counter,
     * failed-login timestamp, and any transient lockout state.
     *
     * @param userId the user's UUID identifier
     */
    void recordSuccessfulLogin(@NonNull UUID userId);

    /**
     * Returns {@code true} if the given user is currently locked (either by
     * threshold breach while still within the backoff window, or by an
     * administrator-set lock where {@code lockedUntil} is null).
     *
     * <p>Does NOT side-effect; call {@link #unlockIfCooldownExpired} first to
     * auto-clear expired cooldowns.
     *
     * @param userId the user's UUID identifier
     * @return {@code true} if the account is locked and the lock has not expired
     */
    boolean isLockedOut(@NonNull UUID userId);

    /**
     * Checks whether the cooldown window has elapsed and, if so, clears the
     * lockout and resets the failed-attempt counter.
     *
     * @param userId the user's UUID identifier
     * @return {@code true} if the account was locked and has now been unlocked
     *         by this call; {@code false} if unlocking was not needed or not
     *         possible
     */
    boolean unlockIfCooldownExpired(@NonNull UUID userId);
}
