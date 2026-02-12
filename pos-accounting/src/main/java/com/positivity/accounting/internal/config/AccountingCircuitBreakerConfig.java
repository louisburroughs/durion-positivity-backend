package com.positivity.accounting.internal.config;

import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.extern.slf4j.Slf4j;

/**
 * Configuration for Resilience4j circuit breaker patterns.
 * Provides circuit breaker configuration for cross-service calls (e.g., to
 * Invoice service).
 *
 * **Circuit Breaker States:**
 * - CLOSED: Normal operation, requests pass through
 * - OPEN: Threshold exceeded, requests fail immediately
 * - HALF_OPEN: Testing if service recovered, limited requests allowed
 *
 * **Configuration:**
 * - Failure threshold: 50% (fail if 50% of calls fail)
 * - Slow call threshold: 100% (fail if 100% of calls are slow)
 * - Slow call duration: 5s (call > 5s is considered slow)
 * - Wait duration in open state: 30s (before attempting half-open)
 *
 * @see <a href=
 *      "https://resilience4j.readme.io/docs/circuitbreaker">Resilience4j
 *      Circuit Breaker</a>
 */
@Slf4j
@Configuration
public class AccountingCircuitBreakerConfig {

        /**
         * Default circuit breaker config for external service calls.
         * Used for Invoice service, and other downstream services.
         */
        @Bean
        public CircuitBreakerRegistry circuitBreakerRegistry() {
                CircuitBreakerConfig defaultConfig = CircuitBreakerConfig.custom()
                                .failureRateThreshold(50.0f) // Fail if 50% of calls fail
                                .slowCallRateThreshold(100.0f) // Fail if all calls are slow
                                .slowCallDurationThreshold(Duration.ofSeconds(5)) // Call > 5s is slow
                                .waitDurationInOpenState(Duration.ofSeconds(30)) // Wait 30s before half-open
                                .permittedNumberOfCallsInHalfOpenState(3) // Allow 3 calls in half-open
                                .minimumNumberOfCalls(10) // Measure after 10 calls
                                .recordExceptions(Exception.class) // Record all exceptions
                                .build();

                return CircuitBreakerRegistry.of(defaultConfig);
        }

        /**
         * Invoice service-specific circuit breaker configuration.
         * More lenient: invoices are critical, but invoice service should be reliable.
         */
        @Bean("invoiceServiceCircuitBreaker")
        public CircuitBreaker invoiceServiceCircuitBreaker(CircuitBreakerRegistry registry) {
                CircuitBreakerConfig invoiceConfig = CircuitBreakerConfig.custom()
                                .failureRateThreshold(50.0f)
                                .slowCallRateThreshold(100.0f)
                                .slowCallDurationThreshold(Duration.ofSeconds(5))
                                .waitDurationInOpenState(Duration.ofSeconds(30))
                                .permittedNumberOfCallsInHalfOpenState(3)
                                .minimumNumberOfCalls(10)
                                .recordExceptions(Exception.class)
                                .build();

                CircuitBreaker circuitBreaker = registry.circuitBreaker("invoice-service", invoiceConfig);

                // Log transitions
                circuitBreaker.getEventPublisher()
                                .onStateTransition(event -> log.warn(
                                                "Invoice service circuit breaker transitioned: {} -> {}",
                                                event.getStateTransition().getFromState(),
                                                event.getStateTransition().getToState()))
                                .onError(event -> log.debug(
                                                "Invoice service call failed: {}",
                                                event.getThrowable().getMessage()))
                                .onSuccess(event -> log.debug(
                                                "Invoice service call succeeded in {}ms",
                                                event.getElapsedDuration().toMillis()));

                return circuitBreaker;
        }
}
