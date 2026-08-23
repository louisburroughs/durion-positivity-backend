package com.positivity.events.outbox;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

/**
 * Makes the {@code event_outbox} drain visible to operators (issue #1458, ADR-0044 §4 operational
 * signals). Every service that publishes domain events through an outbox registers one of these
 * (see the module's {@code internal/config/OutboxHealthConfig}), so a broker outage, a publisher
 * that never ran, or a poison row retrying forever is distinguishable from a healthy service
 * without shell access — the exact blindness that let four separate event-flow faults (#1444,
 * #1447, #1449, #1456) hide behind a green {@code /actuator/health}.
 *
 * <h2>Always UP — a stuck drain is not unserviceability</h2>
 *
 * A stalled outbox does not stop the service serving its own API; it makes downstream replicas go
 * stale. Reporting DOWN for that would restart a working container (the compose healthcheck reads
 * the aggregate {@code /actuator/health} status) and stop dependents from starting — the
 * restart-loop problem that got Kafka health signals disabled in the first place. pos-order's
 * {@code InventoryReplicaHealthIndicator} and pos-supplier's {@code SupplierClientHealthIndicator}
 * carry the same reasoning.
 *
 * <p><strong>Micrometer is the alerting channel, not health.</strong> Two gauges are registered
 * per module, named {@code <prefix>.pending} and {@code <prefix>.oldest.age.seconds}; the
 * Grafana-provisioned alerts in
 * {@code observability/grafana/provisioning/alerting/domain-events-alerts.yml} fire on them across
 * every service. The health details exist so an operator already probing the endpoint sees the
 * drain state without leaving it.
 *
 * <h2>drainState semantics</h2>
 *
 * <ul>
 *   <li>{@code drained} — no unpublished rows.
 *   <li>{@code draining} — a head row exists but is younger than the lag threshold.
 *   <li>{@code stalled-never-attempted} — head older than the threshold with {@code attempts == 0}:
 *       the publisher is not running at all (the #1456 failure shape, invisible to any broker
 *       check).
 *   <li>{@code stalled-retrying} — head older than the threshold with recorded attempts: the
 *       publisher runs but the head row keeps failing (the ~17k-retry serializer failure shape);
 *       read {@code last_error} on the head row for the reason.
 * </ul>
 *
 * <h2>No row count in {@code health()}</h2>
 *
 * Container healthchecks poll this endpoint continuously, so it issues only the indexed
 * first-unpublished-row lookup. The backlog count runs at Prometheus scrape cadence through the
 * {@code <prefix>.pending} gauge instead — same split pos-workorder's original
 * {@code OutboxMetrics} used.
 */
@Slf4j
public final class OutboxHealthContributor implements HealthIndicator {

    /** Made legible in the payload so nobody "fixes" this into a DOWN. */
    static final String STATUS_POLICY = "always UP: a stalled outbox stales downstream replicas but"
            + " does not stop this service serving; alert on the *.outbox.oldest.age.seconds gauge.";

    /**
     * The drain head — the oldest unpublished outbox row, whose age and retry history summarize
     * the whole drain (the publisher stops each batch at the first failure, so one stuck row is
     * always the head). Deliberately excludes {@code last_error}: some services serve
     * {@code /actuator/health} details anonymously ({@code show-details: always} profiles), and
     * raw broker/serializer error text does not belong on an unauthenticated endpoint. The
     * runbook's outbox SQL is the place to read {@code attempts}/{@code last_error} in full.
     */
    public record DrainHead(@NonNull Instant createdAt, int attempts) {}

    private final Clock clock;
    private final Duration lagThreshold;
    private final LongSupplier pendingCount;
    private final Supplier<Optional<DrainHead>> drainHead;

    /**
     * @param meterPrefix gauge name prefix, e.g. {@code "people.outbox"} — kept per-domain so the
     *     scraped series match the established {@code workorder.outbox.*} naming
     * @param meterRegistry nullable: metrics degrade gracefully when no registry is available,
     *     matching the module publishers' own counter registration
     * @param pendingCount unpublished row count (indexed partial count, scrape cadence only)
     * @param drainHead oldest unpublished row, if any
     */
    public OutboxHealthContributor(
            @NonNull String meterPrefix,
            @NonNull Clock clock,
            @NonNull Duration lagThreshold,
            @Nullable MeterRegistry meterRegistry,
            @NonNull LongSupplier pendingCount,
            @NonNull Supplier<Optional<DrainHead>> drainHead) {
        Objects.requireNonNull(meterPrefix, "meterPrefix must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.lagThreshold = Objects.requireNonNull(lagThreshold, "lagThreshold must not be null");
        this.pendingCount = Objects.requireNonNull(pendingCount, "pendingCount must not be null");
        this.drainHead = Objects.requireNonNull(drainHead, "drainHead must not be null");

        if (meterRegistry == null) {
            log.warn("No MeterRegistry available; outbox gauges for {} not registered", meterPrefix);
            return;
        }
        Gauge.builder(meterPrefix + ".pending", this, OutboxHealthContributor::safePendingCount)
                .description("Unpublished event_outbox rows awaiting Kafka publish")
                .register(meterRegistry);
        Gauge.builder(meterPrefix + ".oldest.age.seconds", this, OutboxHealthContributor::safeOldestAgeSeconds)
                .description("Age in seconds of the oldest unpublished event_outbox row (0 when drained)")
                .register(meterRegistry);
    }

    private static double safePendingCount(OutboxHealthContributor self) {
        try {
            return self.pendingCount.getAsLong();
        } catch (Exception e) {
            // A gauge that throws breaks the whole scrape, taking every other metric with it.
            log.warn("Unable to read outbox pending count for the gauge", e);
            return Double.NaN;
        }
    }

    private static double safeOldestAgeSeconds(OutboxHealthContributor self) {
        try {
            return self.drainHead
                    .get()
                    .map(head -> (double) self.age(head.createdAt()).toSeconds())
                    .orElse(0.0);
        } catch (Exception e) {
            log.warn("Unable to read outbox drain-head age for the gauge", e);
            return Double.NaN;
        }
    }

    @Override
    public Health health() {
        // Implements HealthIndicator directly, so Boot does not wrap a thrown exception: anything
        // escaping here surfaces as a 500 from /actuator/health. The guarantee has to be total.
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("statusPolicy", STATUS_POLICY);
        details.put("lagThreshold", lagThreshold.toString());
        try {
            Optional<DrainHead> head = drainHead.get();
            if (head.isEmpty()) {
                details.put("drainState", "drained");
            } else {
                describeHead(head.get(), details);
            }
        } catch (Exception e) {
            log.warn("Unable to read outbox drain head for health details", e);
            details.put("drainState", "unknown");
            details.put("error", e.getClass().getSimpleName());
        }
        return Health.up().withDetails(details).build();
    }

    private void describeHead(DrainHead head, Map<String, Object> details) {
        Duration headAge = age(head.createdAt());
        details.put("oldestCreatedAt", head.createdAt().toString());
        details.put("oldestAgeSeconds", headAge.toSeconds());
        details.put("headAttempts", head.attempts());
        String drainState;
        if (headAge.compareTo(lagThreshold) <= 0) {
            drainState = "draining";
        } else if (head.attempts() == 0) {
            drainState = "stalled-never-attempted";
        } else {
            drainState = "stalled-retrying";
        }
        details.put("drainState", drainState);
    }

    /**
     * Floored at zero: a writer clock slightly ahead of this one would otherwise report a negative
     * age, which reads as a broken gauge rather than as the fresh row it describes.
     */
    private Duration age(Instant createdAt) {
        Duration age = Duration.between(createdAt, Instant.now(clock));
        return age.isNegative() ? Duration.ZERO : age;
    }
}
