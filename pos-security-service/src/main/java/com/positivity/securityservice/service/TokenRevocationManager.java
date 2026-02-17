package com.positivity.securityservice.service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import io.github.resilience4j.retry.Retry;
import io.vavr.control.Try;
import lombok.extern.slf4j.Slf4j;

/**
 * Manages JWT token revocation using Redis for high-performance lookup.
 * 
 * **Purpose:**
 * - Maintain revoked token list (JTI identifiers) with TTL
 * - Provide fast O(1) lookups during token validation
 * - Support async revocation with exponential backoff retry
 * 
 * **Storage Format:**
 * Key: `jwt:revoked:{jti}` (example:
 * `jwt:revoked:550e8400-e29b-41d4-a716-446655440000`)
 * Value: `true` (boolean flag, only key presence matters)
 * TTL: Matches token expiration time (1 hour for access tokens, 7 days for
 * refresh tokens)
 * 
 * **Concurrency:**
 * Implements exponential backoff retry for OptimisticLockingFailureException:
 * - Max attempts: 3
 * - Initial delay: 100ms
 * - Backoff multiplier: 2.0 (100ms → 200ms → 400ms)
 * - Graceful degradation: logs warning if Redis unavailable
 * 
 * **Example Usage:**
 * ```java
 * tokenRevocationManager.revokeToken(jwtToken.getId(), 3600); // 1 hour
 * boolean isRevoked = tokenRevocationManager.isRevoked(jti);
 * ```
 * 
 * @since 1.0
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "security.redis.enabled", havingValue = "true", matchIfMissing = true)
public class TokenRevocationManager {

    private static final String REVOCATION_KEY_PREFIX = "jwt:revoked:";
    private final RedisTemplate<String, Boolean> redisTemplate;
    private final Retry jwtRevocationRetry;

    public TokenRevocationManager(
            RedisTemplate<String, Boolean> jwtRevocationRedisTemplate,
            Retry jwtRevocationRetry) {
        this.redisTemplate = jwtRevocationRedisTemplate;
        this.jwtRevocationRetry = jwtRevocationRetry;
    }

    /**
     * Marks a token as revoked in Redis with exponential backoff retry.
     * 
     * **Concurrency Handling:**
     * If OptimisticLockingFailureException occurs (entity version conflict),
     * automatically retries up to 3 times with exponential backoff.
     * 
     * **Retry Configuration:**
     * - Max attempts: 3
     * - Initial delay: 100ms
     * - Backoff multiplier: 2.0 (100ms → 200ms → 400ms)
     * 
     * @param jti               JWT ID (unique identifier)
     * @param expirationSeconds token expiration time in seconds
     * @return true if successfully revoked, false if Redis unavailable
     * 
     * @throws IllegalArgumentException if jti is blank or expirationSeconds <= 0
     */
    public boolean revokeToken(String jti, long expirationSeconds) {
        if (jti == null || jti.isBlank()) {
            throw new IllegalArgumentException("JTI cannot be blank");
        }
        if (expirationSeconds <= 0) {
            throw new IllegalArgumentException("Expiration seconds must be positive");
        }

        String revocationKey = REVOCATION_KEY_PREFIX + jti;

        // Apply retry decorator with exponential backoff
        // Cast is required for proper type resolution with Resilience4j Retry decorator
        @SuppressWarnings({ "java:S1905", "java:S4276", "RedundantCast" })
        var decoratedOperation = Retry.decorateSupplier(jwtRevocationRetry,
                (java.util.function.Supplier<Boolean>) () -> {
                    redisTemplate.opsForValue().set(revocationKey, true, expirationSeconds, TimeUnit.SECONDS);
                    return true;
                });

        Try<Boolean> result = Try.of(decoratedOperation::get);

        return result
                .onSuccess(success -> log.debug("Token revoked in Redis: jti={}, expirationSeconds={}", jti,
                        expirationSeconds))
                .onFailure(error -> log.warn(
                        "Failed to revoke token in Redis after retries (graceful degradation): jti={}, error={}",
                        jti,
                        error.getClass().getSimpleName()))
                .getOrElse(false);
    }

    /**
     * Checks if a token is revoked.
     * 
     * **Performance:** O(1) Redis lookup (typical latency: 1-5ms)
     * 
     * **Graceful Degradation:**
     * If Redis is unavailable, logs warning and returns false (token is NOT
     * revoked).
     * This prevents authentication failures due to cache unavailability.
     * 
     * @param jti JWT ID to check
     * @return true if token is revoked, false if not revoked or Redis unavailable
     * 
     * @throws IllegalArgumentException if jti is blank
     */
    public boolean isRevoked(String jti) {
        if (jti == null || jti.isBlank()) {
            throw new IllegalArgumentException("JTI cannot be blank");
        }

        String revocationKey = REVOCATION_KEY_PREFIX + jti;

        try {
            Boolean isRevoked = redisTemplate.opsForValue().get(revocationKey);
            boolean revoked = isRevoked != null && isRevoked;

            if (revoked) {
                log.debug("Token found in revocation list: jti={}", jti);
            }

            return revoked;
        } catch (Exception e) {
            log.warn(
                    "Failed to check token revocation in Redis (returning not-revoked): jti={}, error={}",
                    jti,
                    e.getClass().getSimpleName());
            // Graceful degradation: if Redis fails, assume token is NOT revoked
            // This prevents legitimate requests from failing due to cache issues
            return false;
        }
    }

    /**
     * Removes a token from the revocation list (typically for testing).
     * 
     * @param jti JWT ID to un-revoke
     * @return true if successfully removed, false if Redis unavailable or key
     *         doesn't exist
     */
    public boolean unrevokeToken(String jti) {
        if (jti == null || jti.isBlank()) {
            throw new IllegalArgumentException("JTI cannot be blank");
        }

        String revocationKey = REVOCATION_KEY_PREFIX + jti;

        try {
            var result = redisTemplate.delete(revocationKey);
            boolean wasDeleted = Boolean.TRUE.equals(result);
            if (wasDeleted) {
                log.debug("Token un-revoked in Redis: jti={}", jti);
            }
            return wasDeleted;
        } catch (Exception e) {
            log.warn(
                    "Failed to un-revoke token in Redis: jti={}, error={}",
                    jti,
                    e.getClass().getSimpleName());
            return false;
        }
    }

    /**
     * Clears all revoked tokens from Redis (typically for testing or maintenance).
     * 
     * **WARNING:** This method is intended for testing environments only.
     * In production, avoid calling this method as it scans all Redis keys matching
     * the pattern, which can impact performance. Use gradual key expiration (TTL)
     * instead.
     * 
     * Uses SCAN command with batched deletes for non-blocking iteration.
     * 
     * @return number of keys deleted
     */
    public long clearAllRevoked() {
        try (var cursor = redisTemplate.opsForValue()
                .getOperations()
                .scan(org.springframework.data.redis.core.ScanOptions.scanOptions()
                        .match(REVOCATION_KEY_PREFIX + "*")
                        .count(100) // Batch size hint for SCAN
                        .build())) {

            long deletedCount = 0;
            List<String> batch = new ArrayList<>(100);

            while (cursor.hasNext()) {
                batch.add(cursor.next());

                // Delete in batches of 100 to avoid memory buildup
                if (batch.size() >= 100) {
                    deletedCount += redisTemplate.delete(batch);
                    batch.clear();
                }
            }

            // Delete remaining keys
            if (!batch.isEmpty()) {
                deletedCount += redisTemplate.delete(batch);
            }

            log.info("Cleared all revoked tokens from Redis: count={}", deletedCount);
            return deletedCount;

        } catch (Exception e) {
            log.warn("Failed to clear revoked tokens from Redis: error={}", e.getClass().getSimpleName());
            return 0;
        }
    }
}
