package com.positivity.securityservice.internal.service;

import com.positivity.securityservice.internal.config.LockoutPolicy;
import com.positivity.securityservice.internal.entity.User;
import com.positivity.securityservice.internal.repository.UserRepository;
import com.positivity.securityservice.service.LockoutService;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Production implementation of {@link LockoutService} for AUTH-003.
 *
 * <p>
 * Applies progressive backoff lockout policy defined in
 * {@code pos.security.lockout.*} configuration properties.
 *
 * @since AUTH-003
 */
@Service
@RequiredArgsConstructor
public class LockoutServiceImpl implements LockoutService {

    private static final String USER_NOT_FOUND = "User not found: ";
    private final UserRepository userRepository;
    private final LockoutPolicy lockoutPolicy;
    private final Clock clock;

    @Override
    @Transactional
    public void recordFailedAttempt(@NonNull UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UsernameNotFoundException(USER_NOT_FOUND + userId));

        Instant now = Instant.now(clock);
        Instant priorLastFailedAt = user.getLastFailedLoginAt();

        user.setFailedLoginAttempts(user.getFailedLoginAttempts() + 1);
        user.setLastFailedLoginAt(now);

        int attempts = user.getFailedLoginAttempts();
        boolean withinWindow = priorLastFailedAt != null
                && priorLastFailedAt.isAfter(now.minus(lockoutPolicy.window()));

        if (attempts >= lockoutPolicy.maxAttempts() && withinWindow) {
            user.setAccountNonLocked(false);
            user.setLockedAt(now);

            int lockoutCycle = Math.max(1, attempts / lockoutPolicy.maxAttempts());
            long scaleFactor = (long) Math.pow(lockoutPolicy.backoffMultiplier(), lockoutCycle);
            Duration lockoutDuration = lockoutPolicy.window().multipliedBy(scaleFactor);
            if (lockoutDuration.compareTo(lockoutPolicy.maxBackoffWindow()) > 0) {
                lockoutDuration = lockoutPolicy.maxBackoffWindow();
            }
            user.setLockedUntil(now.plus(lockoutDuration));
        }

        userRepository.save(user);
    }

    /**
     * Resets lockout-related state on successful authentication.
     *
     * @param userId persistent user identifier
     */
    @Override
    @Transactional
    public void recordSuccessfulLogin(@NonNull UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UsernameNotFoundException(USER_NOT_FOUND + userId));

        user.setFailedLoginAttempts(0);
        user.setLastSuccessfulLoginAt(Instant.now(clock));
        user.setAccountNonLocked(true);
        user.setLockedAt(null);
        user.setLockedUntil(null);

        userRepository.save(user);
    }

    /**
     * Returns whether the user is currently locked out.
     *
     * @param userId persistent user identifier
     * @return {@code true} when locked with no expiry or a future expiry
     */
    @Override
    public boolean isLockedOut(@NonNull UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UsernameNotFoundException(USER_NOT_FOUND + userId));

        return !user.isAccountNonLocked()
                && (user.getLockedUntil() == null || user.getLockedUntil().isAfter(Instant.now(clock)));
    }

    /**
     * Unlocks a user only when a timed lockout has expired.
     *
     * @param userId persistent user identifier
     * @return {@code true} when an expired lock was cleared and saved
     */
    @Override
    @Transactional
    public boolean unlockIfCooldownExpired(@NonNull UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UsernameNotFoundException(USER_NOT_FOUND + userId));

        if (user.isAccountNonLocked()) {
            return false;
        }
        if (user.getLockedUntil() == null) {
            // Admin-set lock with no expiry — no auto-unlock
            return false;
        }
        if (user.getLockedUntil().isBefore(Instant.now(clock))) {
            user.setAccountNonLocked(true);
            user.setLockedAt(null);
            user.setLockedUntil(null);
            user.setFailedLoginAttempts(0);
            userRepository.save(user);
            return true;
        }
        return false;
    }
}
