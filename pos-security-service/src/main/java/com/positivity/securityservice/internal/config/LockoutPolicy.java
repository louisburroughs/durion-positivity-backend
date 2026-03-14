package com.positivity.securityservice.internal.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Lockout policy configuration bound from {@code pos.security.lockout.*}.
 *
 * <p>
 * Defaults: 5 max attempts, 10-minute evaluation window, 2× progressive
 * backoff multiplier, 30-minute maximum backoff window.
 *
 * @since AUTH-003
 */
@ConfigurationProperties(prefix = "pos.security.lockout")
public record LockoutPolicy(
        int maxAttempts,
        Duration window,
        int backoffMultiplier,
        Duration maxBackoffWindow) {

    public LockoutPolicy {
        if (maxAttempts < 1)
            throw new IllegalArgumentException("maxAttempts must be >= 1");
        if (window == null || window.isNegative() || window.isZero())
            throw new IllegalArgumentException("window must be positive");
        if (backoffMultiplier < 1)
            throw new IllegalArgumentException("backoffMultiplier must be >= 1");
        if (maxBackoffWindow == null || maxBackoffWindow.isNegative() || maxBackoffWindow.isZero())
            throw new IllegalArgumentException("maxBackoffWindow must be positive");
    }
}
