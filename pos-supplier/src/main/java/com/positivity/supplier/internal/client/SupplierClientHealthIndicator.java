package com.positivity.supplier.internal.client;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import org.jspecify.annotations.NonNull;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Reports vendor circuit-breaker state as health <em>details</em> — and <strong>always reports
 * UP</strong>.
 *
 * <p>This looks wrong until you follow what consumes it. {@code docker-compose.yml} gives
 * pos-supplier {@code healthcheck: wget -qO- http://localhost:8080/actuator/health} with
 * {@code retries: 12}, and {@code application.yml} defines <em>no</em> health groups, so every
 * contributor lands in the aggregate status that healthcheck reads. Sibling services in the same
 * compose file gate their startup on {@code condition: service_healthy}.
 *
 * <p>So if an open breaker reported DOWN, a single vendor being unreachable would mark the
 * pos-supplier container unhealthy: Docker would restart it, and any dependent service would refuse
 * to start. That inverts the purpose of the breaker. <strong>A supplier being unreachable is the
 * expected steady state a circuit breaker exists to absorb</strong> — it is emphatically not a
 * statement that this pod is unserviceable. pos-supplier with every vendor down still serves its
 * admin API, its audit reads and its own liveness perfectly well.
 *
 * <p>This is also why resilience4j's {@code registerHealthIndicator} is left off: its default maps
 * OPEN to DOWN, which is precisely the hazard above.
 *
 * <p>Health answers "is this pod serviceable". <strong>Micrometer, not health, is the alerting
 * channel for breaker state</strong> — {@code SupplierClientMetrics} publishes a per-key gauge for
 * exactly that purpose.
 *
 * <p>Details are safe to expose under {@code show-details: when-authorized}: breaker names carry
 * only a profile UUID and a capability constant, never a base URL, credential, or resolved secret.
 */
@Component
public class SupplierClientHealthIndicator implements HealthIndicator {

    private final SupplierBreakerRegistry breakerRegistry;

    public SupplierClientHealthIndicator(@NonNull SupplierBreakerRegistry breakerRegistry) {
        this.breakerRegistry = Objects.requireNonNull(breakerRegistry, "breakerRegistry must not be null");
    }

    @Override
    public Health health() {
        SortedMap<String, String> states = breakerRegistry.breakerStates();
        long open = states.values().stream()
                .filter(state -> "OPEN".equals(state) || "FORCED_OPEN".equals(state))
                .count();
        long halfOpen = states.values().stream().filter("HALF_OPEN"::equals).count();

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("breakerCount", states.size());
        details.put("openBreakers", open);
        details.put("halfOpenBreakers", halfOpen);
        details.put("breakers", states);
        // Make the deliberate choice legible to whoever reads /actuator/health next, so nobody
        // "fixes" this into a DOWN and hands vendors a restart lever over our container.
        details.put(
                "statusPolicy",
                "always UP: vendor reachability is not this service's serviceability."
                        + " Alert on the supplier.client.breaker.state gauge, not on health.");

        // Deliberately Health.up() unconditionally, never status(open > 0 ? DOWN : UP).
        return Health.up().withDetails(details).build();
    }
}
