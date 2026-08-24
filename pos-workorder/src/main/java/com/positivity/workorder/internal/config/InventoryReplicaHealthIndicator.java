package com.positivity.workorder.internal.config;

import com.positivity.workorder.internal.repository.ExtInventoryAvailabilityReplicaRepository;
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
 * Makes the age of the owned-availability replica visible to operators (CAP #1315).
 *
 * <h2>Why this matters more here than on the sales side</h2>
 *
 * A stale replica in pos-order costs a line an unnecessary backorder flag. Here it blocks a part
 * issue: a technician is told a part is unavailable when it is sitting on the shelf. That is a
 * visible, immediate operational problem, and the first question anyone will ask is "is the feed
 * behind?" — so the answer needs to be somewhere they can look.
 *
 * <h2>Still always UP</h2>
 *
 * Staleness is not unserviceability. This module publishes no health groups, so every contributor
 * lands in the aggregate {@code /actuator/health} status — the status a container healthcheck
 * reads and dependent services gate startup on with {@code condition: service_healthy}. Reporting
 * DOWN because a Kafka consumer is behind would restart a service that is working and stop its
 * dependents from starting, turning a lagging feed into an outage. pos-supplier's
 * {@code SupplierClientHealthIndicator} carries the same reasoning.
 *
 * <p><strong>Micrometer is the alerting channel.</strong> Alert on
 * {@code workorder.inventory.replica.age}; these details are for whoever is already looking.
 *
 * <h2>Empty is not stale</h2>
 *
 * A replica holding nothing has never been fed — a wiring or topic problem, and one that blocks
 * every gated part issue outright. An old replica was fed and stopped. Different investigations,
 * so they are reported as different states.
 *
 * <h2>No row count</h2>
 *
 * Deliberately absent. {@code count()} is {@code select count(*)}, a full scan on Postgres, and this
 * endpoint is polled continuously by container healthchecks — a steady scan that grows with the
 * replica, to report a number that answers nothing the newest-fact timestamp does not. "Never fed"
 * is already reported as its own state, which is the only thing a count would have told us.
 *
 * <p>Details are safe under {@code show-details: when-authorized}: timestamps and a row count, no
 * stock figures, part numbers, or location identifiers.
 */
@Component
public class InventoryReplicaHealthIndicator implements HealthIndicator {
    private static final String REPLICA_STATE = "replicaState";

    private static final Logger log = LoggerFactory.getLogger(InventoryReplicaHealthIndicator.class);

    /** Made legible in the payload so nobody "fixes" this into a DOWN. */
    static final String STATUS_POLICY = "always UP: a lagging availability replica blocks part issues,"
            + " not serviceability. Alert on the workorder.inventory.replica.age gauge.";

    static final String GAUGE_NAME = "workorder.inventory.replica.age";

    private final ExtInventoryAvailabilityReplicaRepository availabilityRepository;
    private final Clock clock;
    private final Duration stalenessThreshold;

    public InventoryReplicaHealthIndicator(
            @NonNull ExtInventoryAvailabilityReplicaRepository availabilityRepository,
            @NonNull Clock clock,
            @NonNull MeterRegistry meterRegistry,
            @Value("${workorder.inventory-replica.staleness-threshold:PT10M}") @NonNull Duration stalenessThreshold) {
        this.availabilityRepository = Objects.requireNonNull(availabilityRepository, "repository must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.stalenessThreshold = Objects.requireNonNull(stalenessThreshold, "stalenessThreshold must not be null");

        // Reported as -1 rather than 0 when the replica is empty: zero would read as "fed this
        // instant", the opposite of never fed, and would silence the alert that matters most.
        TimeGauge.builder(GAUGE_NAME, this, TimeUnit.SECONDS, InventoryReplicaHealthIndicator::ageSecondsOrUnfed)
                .description("Seconds since the newest owned-availability fact was applied; -1 when never fed")
                .register(meterRegistry);
    }

    private static double ageSecondsOrUnfed(InventoryReplicaHealthIndicator self) {
        try {
            return self.availabilityRepository
                    .findNewestUpdatedAt()
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
            Optional<Instant> newest = availabilityRepository.findNewestUpdatedAt();
            if (newest.isEmpty()) {
                details.put(REPLICA_STATE, "never-fed");
                details.put("newestFactAt", null);
            } else {
                Duration age = age(newest.get());
                details.put("newestFactAt", newest.get().toString());
                details.put("ageSeconds", age.toSeconds());
                details.put(REPLICA_STATE, age.compareTo(stalenessThreshold) > 0 ? "stale" : "fresh");
            }
        } catch (Exception e) {
            log.warn("Unable to read availability replica staleness", e);
            details.put(REPLICA_STATE, "unknown");
            details.put("error", e.getClass().getSimpleName());
        }
        return Health.up().withDetails(details).build();
    }

    /**
     * Floored at zero: a producer clock slightly ahead of this one would otherwise report a
     * negative age, which reads as a broken gauge rather than the fresh replica it describes.
     */
    private Duration age(Instant newest) {
        Duration age = Duration.between(newest, Instant.now(clock));
        return age.isNegative() ? Duration.ZERO : age;
    }
}
