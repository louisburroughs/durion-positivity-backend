package com.positivity.securityservice.internal.config;

import io.github.resilience4j.core.registry.EntryAddedEvent;
import io.github.resilience4j.core.registry.EntryRemovedEvent;
import io.github.resilience4j.core.registry.EntryReplacedEvent;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Redis configuration for JWT token revocation caching.
 *
 * Enables fast lookup of revoked tokens by storing JTI (JWT ID) keys in Redis
 * with TTL matching the token expiration time. Gracefully degrades if Redis
 * is unavailable (logs warning and continues).
 *
 * **Properties:**
 * - `security.redis.enabled` (default: true) - Enable/disable Redis caching
 * - `spring.redis.host` (default: localhost)
 * - `spring.redis.port` (default: 6379)
 * - `spring.redis.timeout` (default: 2000ms)
 *
 * @since 1.0
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "security.redis.enabled", havingValue = "true", matchIfMissing = true)
public class RedisConfig {

    private static final String JWT_REVOCATION_RETRY = "jwt-revocation-retry";

    /**
     * Redis template for JWT revocation management.
     *
     * <p>Keys are serialized as plain UTF-8 strings. This is deliberate and load-bearing, not
     * cosmetic: {@code RedisTemplate}'s default key serializer is
     * {@code JdkSerializationRedisSerializer}, which writes {@code jwt:revoked:{jti}} as a Java
     * object stream. Under that default the key space is readable only by this template — the
     * {@code jwt:revoked:*} SCAN in {@code TokenRevocationManager.clearAllRevoked()} matches
     * nothing, and no other process can consult a revocation. The API gateway now checks the same
     * key space on every request (#1883), so the encoding is a cross-process contract.
     *
     * <p>Values keep the default serializer: only key <em>presence</em> carries meaning, and the
     * only reader of the value is {@code TokenRevocationManager.isRevoked}, which uses this same
     * template. The gateway issues {@code EXISTS} and never deserializes a value.
     *
     * @param connectionFactory Redis connection factory
     * @return configured RedisTemplate<String, Boolean>
     */
    @Bean
    public RedisTemplate<String, Boolean> jwtRevocationRedisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Boolean> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.afterPropertiesSet();
        return template;
    }

    /**
     * Retry configuration for token revocation operations.
     *
     * Implements exponential backoff with:
     * - Max attempts: 3
     * - Initial delay: 100ms
     * - Multiplier: 2.0 (100ms → 200ms → 400ms)
     *
     * Retries on: OptimisticLockingFailureException,
     * RedisConnectionFailureException
     *
     * @return configured Retry instance
     */
    @Bean
    public Retry jwtRevocationRetry() {
        RetryConfig config = RetryConfig.custom()
                .maxAttempts(3)
                // Exponential backoff with initial delay 100ms and multiplier 2.0 (100ms →
                // 200ms → 400ms)
                .intervalFunction(io.github.resilience4j.core.IntervalFunction.ofExponentialBackoff(100, 2))
                .retryOnException(throwable ->
                        throwable instanceof org.springframework.orm.ObjectOptimisticLockingFailureException
                                || throwable instanceof RuntimeException)
                .build();

        RetryRegistry registry = RetryRegistry.of(config);
        registry.getEventPublisher()
                .onEntryAdded(this::onEntryAdded)
                .onEntryRemoved(this::onEntryRemoved)
                .onEntryReplaced(this::onEntryReplaced);

        return registry.retry(JWT_REVOCATION_RETRY);
    }

    /**
     * Handles retry creation events.
     * Logs at DEBUG level when a new Retry instance is added to the registry.
     */
    private void onEntryAdded(EntryAddedEvent<Retry> event) {
        log.debug(
                "Retry instance created and registered: name={}",
                event.getAddedEntry().getName());
    }

    /**
     * Handles retry removal events.
     * Logs at DEBUG level when a Retry instance is removed from the registry.
     */
    private void onEntryRemoved(EntryRemovedEvent<Retry> event) {
        log.debug(
                "Retry instance removed from registry: name={}",
                event.getRemovedEntry().getName());
    }

    /**
     * Handles retry replacement events.
     * Logs at INFO level when a Retry instance is replaced in the registry,
     * as this may indicate configuration changes.
     */
    private void onEntryReplaced(EntryReplacedEvent<Retry> event) {
        log.info(
                "Retry instance replaced in registry: oldName={}, newName={}",
                event.getOldEntry().getName(),
                event.getNewEntry().getName());
    }
}
