package com.positivity.supplier.internal.client;

import com.positivity.supplier.internal.domain.model.SupplierCapability;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Circuit breakers keyed per {@code (vendorProfileId, capability)}.
 *
 * <p>The key granularity is deliberate: one vendor's stock-inquiry endpoint failing must not
 * fast-fail that vendor's order submission, nor any other vendor. A per-profile or per-module
 * breaker would couple unrelated commercial relationships to each other's outages.
 *
 * <p>Breakers are created on demand and held in a resilience4j {@link CircuitBreakerRegistry}, so
 * state survives across calls. Configured programmatically rather than by annotation because the
 * key is dynamic — an annotation cannot express a per-(profile, capability) name — which also
 * matches the established repo pattern in {@code pos-document-helper}'s {@code DocumentClient}.
 *
 * <p><strong>{@code registerHealthIndicator} is deliberately left off</strong> (its default maps
 * OPEN to DOWN). See {@link SupplierClientHealthIndicator} for why that default is actively
 * dangerous here, and the module pom for the resilience4j version split that makes relying on
 * auto-configured behaviour unwise in this module.
 */
@Component
public class SupplierBreakerRegistry {

    private static final Logger log = LoggerFactory.getLogger(SupplierBreakerRegistry.class);

    private final CircuitBreakerRegistry registry;

    public SupplierBreakerRegistry(
            @Value("${pos.supplier.breaker.failure-rate-threshold:50}") float failureRateThreshold,
            @Value("${pos.supplier.breaker.sliding-window-size:10}") int slidingWindowSize,
            @Value("${pos.supplier.breaker.minimum-number-of-calls:5}") int minimumNumberOfCalls,
            @Value("${pos.supplier.breaker.wait-duration-seconds:30}") long waitDurationSeconds,
            @Value("${pos.supplier.breaker.half-open-permitted-calls:3}") int halfOpenPermittedCalls) {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .failureRateThreshold(failureRateThreshold)
                .slidingWindowSize(slidingWindowSize)
                // Without a minimum call count a single early failure can open a breaker whose
                // window is barely populated, so the first call after a deploy could fast-fail
                // every subsequent one.
                .minimumNumberOfCalls(minimumNumberOfCalls)
                .waitDurationInOpenState(Duration.ofSeconds(waitDurationSeconds))
                .permittedNumberOfCallsInHalfOpenState(halfOpenPermittedCalls)
                .build();
        this.registry = CircuitBreakerRegistry.of(config);
    }

    /**
     * The breaker for one {@code (vendorProfileId, capability)} pair, created on first use.
     *
     * @param vendorProfileId owning profile identity (ADR-0050 §1)
     * @param capability the capability being exercised
     * @return the breaker; the same instance for the same pair
     */
    @NonNull
    public CircuitBreaker breakerFor(@NonNull UUID vendorProfileId, @NonNull SupplierCapability capability) {
        Objects.requireNonNull(vendorProfileId, "vendorProfileId must not be null");
        Objects.requireNonNull(capability, "capability must not be null");
        String name = breakerName(vendorProfileId, capability);
        boolean isNew = registry.find(name).isEmpty();
        CircuitBreaker breaker = registry.circuitBreaker(name);
        if (isNew) {
            // State transitions are operationally interesting and carry no credential material.
            breaker.getEventPublisher()
                    .onStateTransition(event -> log.warn(
                            "Supplier circuit breaker {} transitioned {} -> {}",
                            name,
                            event.getStateTransition().getFromState(),
                            event.getStateTransition().getToState()));
        }
        return breaker;
    }

    /**
     * Breaker name for a key. Stable and parseable: {@code supplier.<vendorProfileId>.<CAPABILITY>}.
     *
     * @param vendorProfileId owning profile identity
     * @param capability the capability
     * @return the breaker name
     */
    @NonNull
    public static String breakerName(@NonNull UUID vendorProfileId, @NonNull SupplierCapability capability) {
        return "supplier." + vendorProfileId + "." + capability.name();
    }

    /**
     * Current state of every breaker created so far, for health details and diagnostics.
     *
     * @return breaker name to state name, ordered by name for stable output; never contains
     *     credential material — names carry only a profile UUID and a capability constant
     */
    @NonNull
    public SortedMap<String, String> breakerStates() {
        SortedMap<String, String> states = new TreeMap<>();
        for (CircuitBreaker breaker : registry.getAllCircuitBreakers()) {
            states.put(breaker.getName(), breaker.getState().name());
        }
        return states;
    }

    /** The underlying registry, for Micrometer binding. */
    @NonNull
    public CircuitBreakerRegistry registry() {
        return registry;
    }

    /** Immutable snapshot of the configured thresholds, for diagnostics. */
    @NonNull
    public Map<String, String> configurationSummary() {
        CircuitBreakerConfig config = registry.getDefaultConfig();
        return Map.of(
                "failureRateThreshold", String.valueOf(config.getFailureRateThreshold()),
                "slidingWindowSize", String.valueOf(config.getSlidingWindowSize()),
                "minimumNumberOfCalls", String.valueOf(config.getMinimumNumberOfCalls()));
    }
}
