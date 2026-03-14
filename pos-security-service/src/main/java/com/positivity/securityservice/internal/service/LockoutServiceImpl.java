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

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Production implementation of {@link LockoutService} for AUTH-003.
 *
 * <p>Applies progressive backoff lockout policy defined in
 * {@code pos.security.lockout.*} configuration properties.
 *
 * @since AUTH-003
 */
@Service
@RequiredArgsConstructor
public class LockoutServiceImpl implements LockoutService {

    private final UserRepository userRepository;
    private final LockoutPolicy lockoutPolicy;

    @Override
    @Transactional
    public void recordFailedAttempt(@NonNull UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + userId));

        Instant now = Instant.now();
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

    @Override
    @Transactional
    public void recordSuccessfulLogin(@NonNull UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + userId));

        user.setFailedLoginAttempts(0);
        user.setLastSuccessfulLoginAt(Instant.now());
        user.setAccountNonLocked(true);
        user.setLockedAt(null);
        user.setLockedUntil(null);

        userRepository.save(user);
    }

    @Override
    public boolean isLockedOut(@NonNull UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + userId));

        return !user.isAccountNonLocked()
                && (user.getLockedUntil() == null || user.getLockedUntil().isAfter(Instant.now()));
    }

    @Override
    @Transactional
    public boolean unlockIfCooldownExpired(@NonNull UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + userId));

        if (user.isAccountNonLocked()) {
            return false;
        }
        if (user.getLockedUntil() == null) {
            // Admin-set lock with no expiry — no auto-unlock
            return false;
        }
        if (user.getLockedUntil().isBefore(Instant.now())) {
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
