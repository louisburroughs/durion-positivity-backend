package com.positivity.supplier.internal.config;

import com.positivity.events.outbox.OutboxHealthContributor;
import com.positivity.supplier.internal.repository.SupplierOutboxEventRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the shared outbox drain health surface (#1458) for this module's
 * {@code supplier_event_outbox} (the one outbox that does not use the standard
 * {@code event_outbox} shape): an always-UP {@code outbox} contributor in
 * {@code /actuator/health} plus the {@code supplier.outbox.pending} and
 * {@code supplier.outbox.oldest.age.seconds} gauges that the domain-events Grafana alerts fire on.
 *
 * <p>Gated on this module's own Kafka flag: a service with eventing deliberately off has no drain
 * to report and gains no health surface — and because the contributor never reports DOWN, enabling
 * it cannot restart-loop a container either way. See {@link OutboxHealthContributor} for the
 * always-UP rationale and the drain-state semantics.
 */
@Configuration
@ConditionalOnProperty(prefix = "pos.supplier.kafka", name = "enabled", havingValue = "true")
public class OutboxHealthConfig {

    @Bean
    public OutboxHealthContributor outboxHealthContributor(
            SupplierOutboxEventRepository outboxEventRepository,
            Clock clock,
            ObjectProvider<MeterRegistry> meterRegistry,
            @Value("${pos.events.outbox.lag-threshold:PT5M}") Duration lagThreshold) {
        return new OutboxHealthContributor(
                "supplier.outbox",
                clock,
                lagThreshold,
                meterRegistry.getIfAvailable(),
                outboxEventRepository::countByPublishedAtIsNull,
                () -> outboxEventRepository
                        .findFirstByPublishedAtIsNullOrderByIdAsc()
                        .map(head -> new OutboxHealthContributor.DrainHead(head.getCreatedAt(), head.getAttempts())));
    }
}
