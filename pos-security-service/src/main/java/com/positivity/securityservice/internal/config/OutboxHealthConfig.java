package com.positivity.securityservice.internal.config;

import com.positivity.events.outbox.OutboxHealthContributor;
import com.positivity.securityservice.internal.repository.OutboxEventRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the shared outbox drain health surface (#1458): an always-UP {@code outbox}
 * contributor in {@code /actuator/health} plus the {@code security.outbox.pending} and
 * {@code security.outbox.oldest.age.seconds} gauges that the domain-events Grafana alerts fire on.
 *
 * <p>Gated on this module's own Kafka flag: a service with eventing deliberately off has no drain
 * to report and gains no health surface — and because the contributor never reports DOWN, enabling
 * it cannot restart-loop a container either way. See {@link OutboxHealthContributor} for the
 * always-UP rationale and the drain-state semantics.
 */
@Configuration
@ConditionalOnProperty(prefix = "pos.security-service.kafka", name = "enabled", havingValue = "true")
public class OutboxHealthConfig {

    @Bean
    public OutboxHealthContributor outboxHealthContributor(
            OutboxEventRepository outboxEventRepository,
            Clock clock,
            ObjectProvider<MeterRegistry> meterRegistry,
            @Value("${pos.events.outbox.lag-threshold:PT5M}") Duration lagThreshold) {
        return new OutboxHealthContributor(
                "security.outbox",
                clock,
                lagThreshold,
                meterRegistry.getIfAvailable(),
                outboxEventRepository::countByPublishedAtIsNull,
                () -> outboxEventRepository
                        .findFirstByPublishedAtIsNullOrderByIdAsc()
                        .map(event -> new OutboxHealthContributor.DrainHead(
                                event.getCreatedAt(), event.getAttempts(), event.getLastError())));
    }
}
