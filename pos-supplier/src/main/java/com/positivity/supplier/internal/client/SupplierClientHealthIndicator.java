package com.positivity.supplier.internal.client;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(SupplierClientHealthIndicator.class);

    /** Made legible in the payload so nobody "fixes" this into a DOWN. */
    static final String STATUS_POLICY = "always UP: vendor reachability is not this service's"
            + " serviceability. Alert on the supplier.client.breaker.state gauge, not on health.";

    private final SupplierBreakerRegistry breakerRegistry;

    public SupplierClientHealthIndicator(@NonNull SupplierBreakerRegistry breakerRegistry) {
        this.breakerRegistry = Objects.requireNonNull(breakerRegistry, "breakerRegistry must not be null");
    }

    @Override
    public Health health() {
        // The guarantee has to be TOTAL, not just the happy path. This class implements
        // HealthIndicator directly rather than extending AbstractHealthIndicator, so Boot does not
        // wrap a thrown exception for us: an exception escaping here would surface as a 500 from
        // /actuator/health, and `wget -qO- .../actuator/health` exits non-zero on a 500 exactly as it
        // does on a DOWN body. That reopens the very restart hazard this class exists to close, and
        // takes every other contributor's report down with it.
        try {
            return report();
        } catch (RuntimeException ex) {
            log.warn("Could not read supplier breaker states for the health endpoint; reporting UP without them", ex);
            return Health.up()
                    .withDetail(
                            "detailsUnavailable",
                            "breaker states could not be read: " + ex.getClass().getSimpleName())
                    .withDetail("statusPolicy", STATUS_POLICY)
                    .build();
        }
    }

    private Health report() {
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
        details.put("statusPolicy", STATUS_POLICY);

        // Deliberately Health.up() unconditionally, never status(open > 0 ? DOWN : UP).
        return Health.up().withDetails(details).build();
    }
}
