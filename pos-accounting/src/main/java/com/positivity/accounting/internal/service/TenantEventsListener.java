package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.repository.ProcessedEventRepository;
import com.positivity.domainevents.tenant.TenantEventTypes;
import com.positivity.kafka.common.KafkaRails;
import com.positivity.tenancy.PlatformTenant;
import com.positivity.tenancy.TenantContext;
import com.positivity.tenancy.replica.TenantProjectionEvent;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Consumes {@code tenant.events.v1} so pos-accounting gives a new tenant its chart of accounts, GL
 * mapping defaults, statement lines and policy defaults (#2526; ADR-0062 §7), modeled on
 * pos-location's and pos-security-service's {@code TenantEventsListener}.
 *
 * <p>Only {@code tenant.created} does anything here; every other tenant lifecycle fact is skipped
 * without a {@code processed_events} row. The group reads from the earliest offset, so facts
 * retained from before this consumer existed are processed once.
 *
 * <p>Consumer contract (ADR-0044 §4): {@code processed_events} idempotency, checked here for a
 * fast skip and again inside the provisioning transaction; an unparsable or malformed record is
 * logged and dropped rather than poisoning the partition; everything else propagates, so the
 * container retries with backoff and routes to {@code tenant.events.v1.dlq}. That includes an empty
 * template: {@code tenant.created} is a one-time fact, and recording it without provisioning would
 * leave the tenant without a chart for good.
 *
 * <p>The template is read under the platform binding <em>before</em> the new tenant is bound, and
 * provisioning then runs entirely under the new tenant's own binding. {@code tenant.created} for
 * the platform tenant itself is recorded and not provisioned: that tenant holds the template.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@KafkaRails
public class TenantEventsListener {

    private final ObjectMapper objectMapper;
    private final ProcessedEventRepository processedEventRepository;
    private final AccountingTemplateReader templateReader;
    private final AccountingTenantProvisioner provisioner;

    @KafkaListener(
            topics = "${pos.accounting.kafka.tenant-events-topic:tenant.events.v1}",
            groupId = "${pos.accounting.kafka.tenant-events-consumer-group:pos-accounting-tenant-events}",
            properties = "auto.offset.reset=earliest")
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
        if (PlatformTenant.isPlatform(tenantId)) {
            log.warn(
                    "tenant.created eventId={} names the platform tenant, which holds the accounting template and is"
                            + " never provisioned; recorded and skipped",
                    eventId);
            provisioner.recordSkipped(eventId);
            return;
        }
        // Read the platform template before binding the new tenant (sequential, not nested), so the
        // provisioning transaction below runs entirely under the new tenant's own binding.
        AccountingTemplate snapshot = templateReader.snapshot();
        // No catch: every failure propagates, so the container error handler retries with backoff
        // and routes to {topic}.dlq (ADR-0044 §4).
        TenantContext.runAs(tenantId, () -> provisioner.provision(tenantId, eventId, snapshot));
    }
}
