package com.positivity.order.internal.config;

import com.positivity.order.internal.repository.ExtInventoryAvailabilityRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.TimeGauge;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Makes the age of the owned-availability replica visible to operators (CAP #1315), so a replica
 * quietly serving month-old numbers is something someone can see rather than something checkout
 * discovers by mislabelling lines.
 *
 * <h2>Always UP — staleness is not unserviceability</h2>
 *
 * A stale replica does not stop this service working. The gate is deliberately built so that an
 * unknown or short answer admits the line as a backorder rather than refusing the sale, and
 * pos-inventory re-decides the same line authoritatively when it handles the reservation-request
 * command. So a lagging replica costs some lines a backorder flag they did not need for a few
 * seconds; it never costs a sale, and pos-order still serves every one of its endpoints.
 *
 * <p>Reporting DOWN for that would be actively harmful. This module publishes no health groups, so
 * every contributor lands in the aggregate {@code /actuator/health} status — the same status a
 * container healthcheck reads and dependent services gate their startup on with
 * {@code condition: service_healthy}. Marking the pod unhealthy because a Kafka consumer is behind
 * would restart a service that is working, and stop its dependents from starting. pos-supplier's
 * {@code SupplierClientHealthIndicator} carries the same reasoning for the same reason.
 *
 * <p><strong>Micrometer is the alerting channel, not health.</strong> The
 * {@code order.inventory.replica.age} gauge is what an alert should fire on; these details exist so
 * an operator already looking at the service can see the number without leaving the endpoint.
 *
 * <h2>Empty is not stale</h2>
 *
 * A replica holding nothing has never been fed — a wiring or topic problem — while an old replica
 * was fed and then stopped. They call for different investigation, so they are reported as
 * different states rather than collapsed into one "stale" boolean.
 *
 * <p>Details are safe under {@code show-details: when-authorized}: they carry timestamps and a row
 * count, no stock figures, SKUs, or location identifiers.
 */
@Component
public class InventoryReplicaHealthIndicator implements HealthIndicator {

    private static final Logger log = LoggerFactory.getLogger(InventoryReplicaHealthIndicator.class);

    /** Made legible in the payload so nobody "fixes" this into a DOWN. */
    static final String STATUS_POLICY = "always UP: a lagging availability replica downgrades line"
            + " labelling, not serviceability. Alert on the order.inventory.replica.age gauge.";

    static final String GAUGE_NAME = "order.inventory.replica.age";

    private final ExtInventoryAvailabilityRepository extInventoryAvailabilityRepository;
    private final Clock clock;
    private final Duration stalenessThreshold;

    public InventoryReplicaHealthIndicator(
            @NonNull ExtInventoryAvailabilityRepository extInventoryAvailabilityRepository,
            @NonNull Clock clock,
            @NonNull MeterRegistry meterRegistry,
            @Value("${pos.order.inventory-replica.staleness-threshold:PT10M}") @NonNull Duration stalenessThreshold) {
        this.extInventoryAvailabilityRepository =
                Objects.requireNonNull(extInventoryAvailabilityRepository, "repository must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.stalenessThreshold = Objects.requireNonNull(stalenessThreshold, "stalenessThreshold must not be null");

        // Reported as -1 rather than 0 when the replica is empty: zero would read as "fed this
        // instant", which is the opposite of never fed, and would silence exactly the alert that
        // an unfed replica should raise.
        TimeGauge.builder(GAUGE_NAME, this, TimeUnit.SECONDS, InventoryReplicaHealthIndicator::ageSecondsOrUnfed)
                .description("Seconds since the newest owned-availability fact was applied; -1 when never fed")
                .register(meterRegistry);
    }

    private static double ageSecondsOrUnfed(InventoryReplicaHealthIndicator self) {
        try {
            return self.newestUpdatedAt()
                    .map(newest -> (double) self.age(newest).toSeconds())
                    .orElse(-1.0);
        } catch (Exception e) {
            // A gauge that throws breaks the whole scrape, taking every other metric with it.
            log.warn("Unable to read availability replica age for the gauge", e);
            return -1.0;
        }
    }

    @Override
    public Health health() {
        // Implements HealthIndicator directly, so Boot does not wrap a thrown exception: anything
        // escaping here surfaces as a 500 from /actuator/health. The guarantee has to be total.
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("statusPolicy", STATUS_POLICY);
        details.put("stalenessThreshold", stalenessThreshold.toString());
        try {
            Optional<Instant> newest = newestUpdatedAt();
            long rows = extInventoryAvailabilityRepository.count();
            details.put("rows", rows);
            if (newest.isEmpty()) {
                details.put("replicaState", "never-fed");
                details.put("newestFactAt", null);
            } else {
                Duration age = age(newest.get());
                details.put("newestFactAt", newest.get().toString());
                details.put("ageSeconds", age.toSeconds());
                details.put("replicaState", age.compareTo(stalenessThreshold) > 0 ? "stale" : "fresh");
            }
        } catch (Exception e) {
            log.warn("Unable to read availability replica staleness", e);
            details.put("replicaState", "unknown");
            details.put("error", e.getClass().getSimpleName());
        }
        return Health.up().withDetails(details).build();
    }

    private Optional<Instant> newestUpdatedAt() {
        return extInventoryAvailabilityRepository.findNewestUpdatedAt();
    }

    /**
     * Floored at zero: a producer clock slightly ahead of this one would otherwise report a
     * negative age, which reads as a broken gauge rather than as the fresh replica it describes.
     */
    private Duration age(Instant newest) {
        Duration age = Duration.between(newest, Instant.now(clock));
        return age.isNegative() ? Duration.ZERO : age;
    }
}
