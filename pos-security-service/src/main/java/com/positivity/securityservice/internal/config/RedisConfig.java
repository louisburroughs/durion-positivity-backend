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
     * Uses String serialization for both keys and values.
     * 
     * @param connectionFactory Redis connection factory
     * @return configured RedisTemplate<String, Boolean>
     */
    @Bean
    public RedisTemplate<String, Boolean> jwtRevocationRedisTemplate(
            RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Boolean> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
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
                .retryOnException(
                        throwable -> throwable instanceof org.springframework.orm.ObjectOptimisticLockingFailureException
                                ||
                                throwable instanceof RuntimeException)
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
        log.debug("Retry instance created and registered: name={}", event.getAddedEntry().getName());
    }

    /**
     * Handles retry removal events.
     * Logs at DEBUG level when a Retry instance is removed from the registry.
     */
    private void onEntryRemoved(EntryRemovedEvent<Retry> event) {
        log.debug("Retry instance removed from registry: name={}", event.getRemovedEntry().getName());
    }

    /**
     * Handles retry replacement events.
     * Logs at INFO level when a Retry instance is replaced in the registry,
     * as this may indicate configuration changes.
     */
    private void onEntryReplaced(EntryReplacedEvent<Retry> event) {
        log.info("Retry instance replaced in registry: oldName={}, newName={}",
                event.getOldEntry().getName(),
                event.getNewEntry().getName());
    }
}
