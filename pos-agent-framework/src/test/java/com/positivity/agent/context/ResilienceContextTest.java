package com.positivity.agent.context;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ResilienceContext
 * Tests context model for resilience engineering scenarios
 * 
 * Requirements: REQ-015.1 - Circuit breaker configuration guidance
 */
class ResilienceContextTest {

    private ResilienceContext context;
    private final String contextId = "resilience-context-123";
    private final String sessionId = "session-456";

    @BeforeEach
    void setUp() {
        context = new ResilienceContext(contextId, sessionId);
    }

    @Test
    @DisplayName("Should create resilience context with valid parameters")
    void shouldCreateResilienceContext() {
        assertNotNull(context);
        assertEquals(contextId, context.getContextId());
        assertEquals(sessionId, context.getSessionId());
        assertNotNull(context.getCreatedAt());
        assertTrue(context.getCreatedAt().isBefore(Instant.now().plusSeconds(1)));
    }

    @Test
    @DisplayName("Should add circuit breaker configuration")
    void shouldAddCircuitBreakerConfiguration() {
        Map<String, Object> config = Map.of(
                "failureThreshold", 5,
                "timeout", "30s",
                "resetTimeout", "60s");

        context.addCircuitBreaker("user-service", config);

        Map<String, Object> configs = context.getCircuitBreakerConfigurations();
        assertTrue(configs.containsKey("user-service"));
        assertEquals(config, configs.get("user-service"));
    }

    @Test
    @DisplayName("Should add retry pattern configuration")
    void shouldAddRetryPatternConfiguration() {
        Map<String, Object> retryConfig = Map.of(
                "maxAttempts", 3,
                "backoffMultiplier", 2.0,
                "initialDelay", "1s");

        context.addRetryPattern("payment-service", retryConfig);

        Map<String, Object> configs = context.getRetryConfigurations();
        assertTrue(configs.containsKey("payment-service"));
        assertEquals(retryConfig, configs.get("payment-service"));
    }
}