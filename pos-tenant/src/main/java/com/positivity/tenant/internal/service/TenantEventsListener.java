package com.positivity.tenant.internal.service;

import com.positivity.domainevents.tenant.TenantEventTypes;
import com.positivity.tenancy.PlatformTenant;
import com.positivity.tenancy.TenantContext;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Consumes {@code tenant.events.v1} for the one fact this module does not produce:
 * {@code tenant.provisioned} from pos-security-service (ADR-0062 §7), which moves the tenant from
 * {@code PENDING} to {@code ACTIVE}. Every other type on the topic is this module's own output and
 * is ignored.
 *
 * <p>The record's tenant header names the tenant that was provisioned (the handler in
 * pos-security-service runs as that tenant), but the registry row lives in the platform tenant, so
 * the work is re-bound to {@link PlatformTenant#ID} explicitly. This is the documented exception to
 * "application code never binds a tenant": pos-tenant's data is platform data by definition.
 * Idempotent by {@code eventId} through {@code processed_events} ({@link TenantProvisioningHandler}).
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "pos.tenant.kafka", name = "enabled", havingValue = "true")
public class TenantEventsListener {

    private final ObjectMapper objectMapper;
    private final TenantProvisioningHandler provisioningHandler;

    @KafkaListener(
            topics = "${pos.tenant.kafka.events-topic:tenant.events.v1}",
            groupId = "${pos.tenant.kafka.events-consumer-group:pos-tenant-events}")
    public void onEvent(@NonNull String message) {
        JsonNode root;
        String eventType;
        try {
            root = objectMapper.readTree(message);
            eventType = root.path("eventType").stringValue(null);
        } catch (Exception e) {
            log.error("Failed to parse tenant.events.v1 message: {}", message, e);
            return;
        }
        if (!TenantEventTypes.PROVISIONED.equals(eventType)) {
            return;
        }
        String eventId = root.path("eventId").stringValue(null);
        String tenantId = root.path("payload").path("tenantId").stringValue(null);
        if (eventId == null || tenantId == null) {
            log.warn("Ignoring tenant.provisioned without eventId/payload.tenantId: {}", message);
            return;
        }
        UUID id;
        try {
            id = UUID.fromString(tenantId);
        } catch (IllegalArgumentException e) {
            log.warn("Ignoring tenant.provisioned with malformed tenantId={}", tenantId);
            return;
        }
        try {
            TenantContext.runAs(PlatformTenant.ID, () -> provisioningHandler.apply(eventId, id));
        } catch (TransientDataAccessException e) {
            // Let the container error handler retry with backoff and route to {topic}.dlq.
            throw e;
        }
    }
}
