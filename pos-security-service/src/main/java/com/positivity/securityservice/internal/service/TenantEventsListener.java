package com.positivity.securityservice.internal.service;

import com.positivity.securityservice.internal.entity.ExtTenant;
import com.positivity.securityservice.internal.entity.ProcessedEvent;
import com.positivity.securityservice.internal.repository.ExtTenantRepository;
import com.positivity.securityservice.internal.repository.ProcessedEventRepository;
import com.positivity.tenancy.replica.TenantProjectionEvent;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Consumes {@code tenant.events.v1} into the global {@code ext_tenant} replica (ADR-0062 §7,
 * plan WS2b). Every projection fact ({@code tenant.created}, {@code tenant.updated},
 * {@code tenant.suspended}, {@code tenant.reactivated}, {@code tenant.decommissioned}) is upserted
 * by tenant id, guarded by the envelope's {@code aggregateVersion} so an older fact never overwrites
 * a newer row. {@code tenant.provisioned} carries no projection and is recorded and skipped.
 *
 * <p>Provisioning of a new tenant on {@code tenant.created} (role template, initial administrator,
 * the {@code tenant.provisioned} answer) is the second WS2b delivery and is not done here yet.
 * Idempotent through {@code processed_events} in the apply transaction; transient database errors
 * rethrow for container retry and dead-lettering (ADR-0044 §4). Both tables are global, so the
 * tenant the record interceptor bound (the platform tenant, since pos-tenant's rows are platform
 * rows) is irrelevant to the write.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "pos.security-service.kafka", name = "enabled", havingValue = "true")
public class TenantEventsListener {

    static final String OWNER = "tenant";

    private final ObjectMapper objectMapper;
    private final TenantReplicaApplier applier;

    public TenantEventsListener(ObjectMapper objectMapper, TenantReplicaApplier applier) {
        this.objectMapper = objectMapper;
        this.applier = applier;
    }

    @KafkaListener(
            topics = "${pos.security-service.kafka.tenant-events-topic:tenant.events.v1}",
            groupId = "${pos.security-service.kafka.tenant-events-consumer-group:pos-security-tenant-events}",
            autoStartup = "false")
    public void onEvent(@NonNull String message) {
        JsonNode root;
        try {
            root = objectMapper.readTree(message);
        } catch (Exception e) {
            log.error("Failed to parse tenant.events.v1 message: {}", message, e);
            return;
        }
        String eventId = root.path("eventId").stringValue(null);
        if (eventId == null || eventId.isBlank()) {
            log.warn("Ignoring tenant.events.v1 message without eventId: {}", message);
            return;
        }
        Optional<TenantProjectionEvent> projection = TenantProjectionEvent.parse(root);
        try {
            applier.apply(eventId, projection.orElse(null));
        } catch (TransientDataAccessException e) {
            // Let the container error handler retry with backoff and route to {topic}.dlq.
            throw e;
        }
    }

    /**
     * The apply transaction, a separate bean so the {@code @Transactional} proxy is honoured when
     * the listener calls it.
     */
    @Component
    @RequiredArgsConstructor
    public static class TenantReplicaApplier {

        private final ExtTenantRepository extTenantRepository;
        private final ProcessedEventRepository processedEventRepository;
        private final Clock clock;

        @Transactional
        public void apply(@NonNull String eventId, TenantProjectionEvent projection) {
            if (processedEventRepository.existsById(eventId)) {
                log.debug("tenant.events.v1 eventId={} already applied", eventId);
                return;
            }
            if (projection != null) {
                upsert(projection);
            }
            processedEventRepository.save(ProcessedEvent.builder()
                    .eventId(eventId)
                    .owner(OWNER)
                    .processedAt(Instant.now(clock))
                    .build());
        }

        private void upsert(TenantProjectionEvent projection) {
            ExtTenant existing =
                    extTenantRepository.findById(projection.tenantId()).orElse(null);
            if (existing != null && existing.getAggregateVersion() > projection.aggregateVersion()) {
                log.debug(
                        "ext_tenant {} holds version {} > incoming {}; keeping the newer row",
                        projection.tenantId(),
                        existing.getAggregateVersion(),
                        projection.aggregateVersion());
                return;
            }
            ExtTenant row = existing != null
                    ? existing
                    : ExtTenant.builder().tenantId(projection.tenantId()).build();
            row.setSlug(projection.slug());
            row.setDisplayName(projection.displayName());
            row.setStatus(projection.status());
            row.setAggregateVersion(projection.aggregateVersion());
            row.setUpdatedAt(Instant.now(clock));
            extTenantRepository.save(row);
            log.info(
                    "ext_tenant applied {} tenant={} slug={} status={} version={}",
                    projection.eventType(),
                    projection.tenantId(),
                    projection.slug(),
                    projection.status(),
                    projection.aggregateVersion());
        }
    }
}
