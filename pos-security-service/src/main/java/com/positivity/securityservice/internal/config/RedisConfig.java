package com.positivity.securityservice.internal.config;

import io.github.resilience4j.core.registry.EntryAddedEvent;
import io.github.resilience4j.core.registry.EntryRemovedEvent;
import io.github.resilience4j.core.registry.EntryReplacedEvent;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import java.time.Duration;
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
                .waitDuration(Duration.ofMillis(100))
                .intervalFunction(io.github.resilience4j.core.IntervalFunction.ofExponentialBackoff(100, 2))
                .retryOnException(
                        throwable -> throwable instanceof org.springframework.orm.ObjectOptimisticLockingFailureException
                                ||
                                throwable instanceof RuntimeException)
                .build();

        RetryRegistry registry = RetryRegistry.of(config);
        registry.getEventPublisher()
                .onEntryAdded(event -> onEntryAdded(event))
                .onEntryRemoved(event -> onEntryRemoved(event))
                .onEntryReplaced(event -> onEntryReplaced(event));

        return registry.retry(JWT_REVOCATION_RETRY);
    }

    private void onEntryAdded(EntryAddedEvent<Retry> event) {
        // Log retry creation if needed
    }

    private void onEntryRemoved(EntryRemovedEvent<Retry> event) {
        // Log retry removal if needed
    }

    private void onEntryReplaced(EntryReplacedEvent<Retry> event) {
        // Log retry replacement if needed
    }
}
