package com.positivity.supplier.internal.client;

import com.positivity.supplier.internal.domain.model.SupplierCapability;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

/**
 * Micrometer instrumentation per {@code (vendorProfileId, capability)}.
 *
 * <p><strong>This — not the health endpoint — is the alerting channel for breaker state.</strong>
 * {@link SupplierClientHealthIndicator} always reports UP by design, because a vendor outage must
 * never mark this pod unserviceable and hand a vendor a restart lever over our container. The
 * operational signal lives here instead:
 *
 * <ul>
 *   <li>{@code supplier.client.breaker.state} — gauge, 0 CLOSED / 1 HALF_OPEN / 2 OPEN /
 *       3 FORCED_OPEN / 4 DISABLED / 5 METRICS_ONLY. Alert on this.
 *   <li>{@code supplier.client.exchanges} — timer per attempt, tagged with the outcome, so latency
 *       and error rate share one series.
 * </ul>
 *
 * <p>Tag cardinality is bounded by configuration, not by traffic: {@code vendorProfileId} has one
 * value per configured supplier connection (a handful) and {@code capability} is a closed enum.
 * Deliberately <strong>no</strong> tag for URI, correlation id or account number — those are
 * unbounded and would blow up the registry. {@code supplierRef} is also omitted: it is renameable,
 * so a rename would silently fork every series.
 */
@Component
public class SupplierClientMetrics {

    static final String EXCHANGE_TIMER = "supplier.client.exchanges";
    static final String BREAKER_STATE_GAUGE = "supplier.client.breaker.state";

    private final MeterRegistry meterRegistry;

    /** Backing values for registered breaker-state gauges, keyed by breaker name. */
    private final ConcurrentMap<String, AtomicInteger> breakerStateValues = new ConcurrentHashMap<>();

    public SupplierClientMetrics(@NonNull MeterRegistry meterRegistry) {
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry must not be null");
    }

    /**
     * Records one completed attempt — including failures, so error rate is derivable from the same
     * series as latency.
     *
     * @param vendorProfileId owning profile identity
     * @param capability the capability exercised
     * @param outcome classification of the attempt
     * @param duration wall-clock duration of the attempt
     */
    public void recordExchange(
            @NonNull UUID vendorProfileId,
            @NonNull SupplierCapability capability,
            @NonNull ExchangeOutcome outcome,
            @NonNull Duration duration) {
        Timer.builder(EXCHANGE_TIMER)
                .description("Outbound supplier exchange attempts, including failures")
                .tags(Tags.of(
                        "vendorProfileId", vendorProfileId.toString(),
                        "capability", capability.name(),
                        "outcome", outcome.name()))
                .register(meterRegistry)
                .record(duration);
    }

    /**
     * Publishes (or updates) the breaker-state gauge for a key. Called on every exchange so the
     * gauge exists as soon as a binding is used and tracks transitions without an event listener.
     *
     * @param vendorProfileId owning profile identity
     * @param capability the capability exercised
     * @param breaker the breaker for that key
     */
    public void recordBreakerState(
            @NonNull UUID vendorProfileId, @NonNull SupplierCapability capability, @NonNull CircuitBreaker breaker) {
        String name = SupplierBreakerRegistry.breakerName(vendorProfileId, capability);
        AtomicInteger holder = breakerStateValues.computeIfAbsent(name, key -> {
            AtomicInteger value = new AtomicInteger(stateCode(breaker.getState()));
            meterRegistry.gauge(
                    BREAKER_STATE_GAUGE,
                    Tags.of(
                            "vendorProfileId", vendorProfileId.toString(),
                            "capability", capability.name()),
                    value,
                    AtomicInteger::doubleValue);
            return value;
        });
        holder.set(stateCode(breaker.getState()));
    }

    /** Numeric encoding of breaker state; ordinal is deliberately not used so it stays stable. */
    static int stateCode(CircuitBreaker.State state) {
        return switch (state) {
            case CLOSED -> 0;
            case HALF_OPEN -> 1;
            case OPEN -> 2;
            case FORCED_OPEN -> 3;
            case DISABLED -> 4;
            case METRICS_ONLY -> 5;
        };
    }
}
