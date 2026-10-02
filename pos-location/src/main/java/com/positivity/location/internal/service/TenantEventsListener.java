package com.positivity.location.internal.service;

import com.positivity.domainevents.tenant.TenantEventTypes;
import com.positivity.location.internal.repository.ProcessedEventRepository;
import com.positivity.tenancy.TenantContext;
import com.positivity.tenancy.replica.TenantProjectionEvent;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Consumes {@code tenant.events.v1} so pos-location can seed a new tenant's bay specialty map
 * (DECISION-LOCATION-025), modeled on pos-security-service's {@code TenantEventsListener}.
 *
 * <p>Only {@code tenant.created} does anything here; every other tenant lifecycle fact on this
 * topic is skipped without a {@code processed_events} row, matching {@code CatalogEventsListener}'s
 * shape for a topic this module only partially cares about — nothing counts those events, so
 * recording them would just be unused bookkeeping.
 *
 * <p>Consumer contract, matching the module's other listeners: {@code processed_events}
 * idempotency (checked here for a fast skip, and again inside the provisioning transaction against
 * the redelivery race), transient DB errors rethrown for container retry/DLQ, an unparsable or
 * non-projection payload logged and dropped rather than poisoning the partition.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "pos.location.kafka", name = "enabled", havingValue = "true")
public class TenantEventsListener {

    private final ObjectMapper objectMapper;
    private final ProcessedEventRepository processedEventRepository;
    private final BaySpecialtyMapProvisioningService provisioningService;

    @KafkaListener(
            topics = "${pos.location.kafka.tenant-events-topic:tenant.events.v1}",
            groupId = "${pos.location.kafka.tenant-events-consumer-group:pos-location-tenant-events}")
    public void onTenantEvent(@NonNull String message) {
        JsonNode root;
        try {
            root = objectMapper.readTree(message);
        } catch (Exception e) {
            log.warn("Skipping unparsable tenant event", e);
            return;
        }
        if (!TenantEventTypes.CREATED.equals(root.path("eventType").stringValue(null))) {
            return;
        }
        String eventId = root.path("eventId").stringValue(null);
        if (eventId == null || eventId.isBlank()) {
            log.warn("Skipping tenant.created event without eventId");
            return;
        }
        if (processedEventRepository.existsById(eventId)) {
            return;
        }
        Optional<TenantProjectionEvent> projection = TenantProjectionEvent.parse(root);
        if (projection.isEmpty()) {
            log.warn("Skipping malformed tenant.created event eventId={}", eventId);
            return;
        }
        UUID tenantId = projection.get().tenantId();

        // Read the platform template before binding the new tenant (sequential, not nested — the
        // same shape pos-security-service's listener uses for its role-template read), so the
        // provisioning transaction below runs entirely under the new tenant's own binding.
        List<BaySpecialtyMapProvisioningService.PlatformRow> platformRows = provisioningService.readPlatformTemplate();

        // No catch: every failure propagates, so the container error handler retries with backoff
        // and routes to {topic}.dlq (ADR-0044 §4).
        TenantContext.runAs(tenantId, () -> provisioningService.provisionIfNeeded(tenantId, eventId, platformRows));
    }
}
