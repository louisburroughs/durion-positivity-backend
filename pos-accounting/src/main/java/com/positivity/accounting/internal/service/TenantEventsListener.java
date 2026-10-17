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
 * <p>{@code tenant.created} stores the tenant's locale in the {@code ext_tenant} replica (#2618) and then provisions;
 * the other projection facts ({@code tenant.updated} and the status changes) store the locale when they carry one,
 * guarded by {@code aggregateVersion}, with the eventId recorded in the same transaction as the upsert. A fact
 * without a locale, and {@code tenant.provisioned}, are skipped without a {@code processed_events} row. Each write
 * runs bound to the fact's tenant in its own transaction, never nested in provisioning. The locale write comes first
 * because provisioning records the eventId: a failure anywhere redelivers the fact, the version-guarded upsert
 * repeats harmlessly, and provisioning runs once. Readers of a tenant with no row see {@code en-US}.
 *
 * <p>The group reads from the earliest offset, so facts retained from before this consumer existed are processed
 * once.
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
    private final TenantLocaleReplicaWriter localeWriter;

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
        String eventType = root.path("eventType").stringValue(null);
        if (eventType == null || !TenantProjectionEvent.PROJECTION_EVENT_TYPES.contains(eventType)) {
            return;
        }
        boolean created = TenantEventTypes.CREATED.equals(eventType);
        String eventId = root.path("eventId").stringValue(null);
        if (eventId == null || eventId.isBlank()) {
            log.warn("Skipping {} event without eventId", eventType);
            return;
        }
        if (!created && !carriesLocale(root)) {
            return;
        }
        if (processedEventRepository.existsById(eventId)) {
            return;
        }
        Optional<TenantProjectionEvent> projection = TenantProjectionEvent.parse(root);
        if (projection.isEmpty()) {
            log.warn("Skipping malformed {} event eventId={}", eventType, eventId);
            return;
        }
        if (!created) {
            TenantProjectionEvent fact = projection.get();
            TenantContext.runAs(fact.tenantId(), () -> localeWriter.applyAndRecord(fact));
            return;
        }
        UUID tenantId = projection.get().tenantId();
        if (PlatformTenant.isPlatform(tenantId)) {
            log.warn(
                    "tenant.created eventId={} names the platform tenant, which holds the accounting template and is"
                            + " never provisioned; recorded and skipped",
                    eventId);
            // Its locale is still the platform's own (en-US), stored under its own binding, and before the eventId is
            // recorded, for the same reason as below: a failed write must redeliver.
            TenantContext.runAs(tenantId, () -> localeWriter.apply(projection.get()));
            provisioner.recordSkipped(eventId);
            return;
        }
        // The locale first, in its own transaction under the tenant's own binding (#2618): a version-guarded
        // upsert that a redelivery repeats harmlessly. Provisioning records the eventId, so a locale write after
        // it could never be retried once provisioning had committed; before it, a failed provisioning redelivers
        // both.
        TenantContext.runAs(tenantId, () -> localeWriter.apply(projection.get()));
        // Read the platform template before binding the new tenant (sequential, not nested), so the
        // provisioning transaction below runs entirely under the new tenant's own binding.
        AccountingTemplate snapshot = templateReader.snapshot();
        // No catch: every failure propagates, so the container error handler retries with backoff
        // and routes to {topic}.dlq (ADR-0044 §4).
        TenantContext.runAs(tenantId, () -> provisioner.provision(tenantId, eventId, snapshot));
    }

    private static boolean carriesLocale(JsonNode root) {
        String locale = root.path("payload").path("locale").stringValue(null);
        return locale != null && !locale.isBlank();
    }
}
