package com.positivity.tenant.internal.service;

import com.positivity.domainevents.DomainEventEnvelope;
import com.positivity.domainevents.tenant.TenantCreatedV1;
import com.positivity.domainevents.tenant.TenantEventTypes;
import com.positivity.domainevents.tenant.TenantProjectionV1;
import com.positivity.domainevents.tenant.TenantReadingAllowanceV1;
import com.positivity.tenant.internal.config.OutboxEventWriter;
import com.positivity.tenant.internal.entity.ReadingTierEntity;
import com.positivity.tenant.internal.entity.TenantEntity;
import java.time.Clock;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Publishes tenant registry facts to {@code tenant.events.v1} through the transactional outbox
 * (ADR-0062 §7, ADR-0044 §6).
 *
 * <p>Called by {@link TenantServiceImpl} after {@code saveAndFlush}, so the entity's {@code
 * @Version} already reflects the mutation and the envelope's {@code aggregateVersion} strictly
 * increases per committed change of the tenant row. One exception: {@link #readingAllowanceChanged}
 * (#2619) is also emitted when a tier is repriced, which changes no tenant row, so two allowance
 * facts may carry the same version; a replica of that fact applies an equal version (older only is
 * ignored). When the Kafka rails are off (broker-less dev/test profiles) the writer bean is absent
 * and every method is a no-op.
 */
@Slf4j
@Component
public class TenantFactPublisher {

    private static final String SOURCE = "pos-tenant";

    private final ObjectProvider<OutboxEventWriter> outboxEventWriter;
    private final Clock clock;
    private final String eventsTopic;

    public TenantFactPublisher(
            ObjectProvider<OutboxEventWriter> outboxEventWriter,
            Clock clock,
            @Value("${pos.tenant.kafka.events-topic:tenant.events.v1}") String eventsTopic) {
        this.outboxEventWriter = outboxEventWriter;
        this.clock = clock;
        this.eventsTopic = eventsTopic;
    }

    public void tenantCreated(@NonNull TenantEntity tenant) {
        TenantCreatedV1 payload = new TenantCreatedV1(
                tenant.getId(),
                tenant.getSlug(),
                tenant.getDisplayName(),
                tenant.getStatus().name(),
                tenant.getInitialAdminEmail(),
                tenant.getLocale());
        publish(TenantCreatedV1.EVENT_TYPE, TenantCreatedV1.SCHEMA_VERSION, tenant, payload);
    }

    /**
     * {@code tenant.reading-allowance.changed} (#2619): the tenant's allowance from {@code tier}, keyed by the tenant
     * and versioned by the tenant row (the consumer applies an equal version, so a tier change that leaves the tenant
     * row untouched still lands). Never part of the public projection.
     */
    public void readingAllowanceChanged(@NonNull TenantEntity tenant, @NonNull ReadingTierEntity tier) {
        TenantReadingAllowanceV1 payload = new TenantReadingAllowanceV1(
                tenant.getId(),
                tier.getCode(),
                new TenantReadingAllowanceV1.MonthlyAllowance(
                        tier.getMonthlyAllowanceAmount(), tier.getMonthlyAllowanceCurrency()),
                Instant.now(clock));
        publish(TenantReadingAllowanceV1.EVENT_TYPE, TenantReadingAllowanceV1.SCHEMA_VERSION, tenant, payload);
    }

    public void tenantUpdated(@NonNull TenantEntity tenant) {
        publishProjection(TenantEventTypes.UPDATED, tenant);
    }

    public void tenantSuspended(@NonNull TenantEntity tenant) {
        publishProjection(TenantEventTypes.SUSPENDED, tenant);
    }

    public void tenantReactivated(@NonNull TenantEntity tenant) {
        publishProjection(TenantEventTypes.REACTIVATED, tenant);
    }

    public void tenantDecommissioned(@NonNull TenantEntity tenant) {
        publishProjection(TenantEventTypes.DECOMMISSIONED, tenant);
    }

    private void publishProjection(String eventType, TenantEntity tenant) {
        TenantProjectionV1 payload = new TenantProjectionV1(
                tenant.getId(),
                tenant.getSlug(),
                tenant.getDisplayName(),
                tenant.getStatus().name(),
                tenant.getLocale());
        publish(eventType, TenantProjectionV1.SCHEMA_VERSION, tenant, payload);
    }

    private <T> void publish(String eventType, int schemaVersion, TenantEntity tenant, T payload) {
        OutboxEventWriter writer = outboxEventWriter.getIfAvailable();
        if (writer == null) {
            return;
        }
        long version = tenant.getVersion() == null ? 0L : tenant.getVersion();
        DomainEventEnvelope<T> envelope = DomainEventEnvelope.of(
                eventType, schemaVersion, tenant.getId(), version, SOURCE, null, null, payload, clock);
        writer.publish(eventsTopic, envelope);
        log.debug("Queued {} for tenant id={} version={}", eventType, tenant.getId(), version);
    }
}
