package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.entity.ExtCustomerBillingRules;
import com.positivity.accounting.internal.entity.ExtCustomerParty;
import com.positivity.accounting.internal.entity.ProcessedEvent;
import com.positivity.accounting.internal.repository.ExtCustomerBillingRulesRepository;
import com.positivity.accounting.internal.repository.ExtCustomerPartyRepository;
import com.positivity.accounting.internal.repository.ProcessedEventRepository;
import com.positivity.domainevents.ReplicaVersionGuard;
import com.positivity.domainevents.customer.BillingRulesUpdatedV1;
import com.positivity.domainevents.customer.CustomerPartyDeletedV1;
import com.positivity.domainevents.customer.CustomerPartyUpdatedV1;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Consumes {@code customer.events.v1} into accounting's read-only replicas (ADR-0044, #842).
 *
 * <p>Materializes {@code customer.billing-rules.updated} into {@code ext_customer_billing_rules}
 * and, since issue #1779, {@code customer.party.updated}/{@code customer.party.deleted} into
 * {@code ext_customer_party} — the display name and customer number accounting responses show in
 * place of a party UUID. Other event types on the topic are ignored. Idempotent via
 * {@code processed_events} (same transaction as the replica upsert). Transient DB errors propagate
 * to the container error handler for retry/DLQ; malformed payloads are logged and skipped (poison
 * messages must not block the partition).
 *
 * <p>The stale guard is {@link ReplicaVersionGuard} (#1486): pos-customer's party
 * {@code aggregateVersion} strictly advances, so a held row is stale only when its version is
 * strictly greater than the incoming fact's — an equal version applies, both because it is an
 * idempotent no-op for live traffic and because it is what would let a future
 * regenerate-from-state replay repair a replica that holds the version number but wrong or missing
 * data. An incoming {@code aggregateVersion} of 0 is legitimate only for a brand-new party (a
 * fresh {@code @Version} row starts at 0), where no replica row exists yet and the guard is never
 * consulted — the fact lands. Once a replica holds any higher version, an incoming 0 can only be a
 * legacy or malformed envelope, and treating it as stale is the safer read.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "pos.accounting.kafka", name = "enabled", havingValue = "true")
public class CustomerEventsListener {

    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final ProcessedEventRepository processedEventRepository;
    private final ExtCustomerBillingRulesRepository billingRulesRepository;
    private final ExtCustomerPartyRepository partyRepository;
    private final Counter payloadRejectedCounter;

    public CustomerEventsListener(
            Clock clock,
            ObjectMapper objectMapper,
            ProcessedEventRepository processedEventRepository,
            ExtCustomerBillingRulesRepository billingRulesRepository,
            ExtCustomerPartyRepository partyRepository,
            ObjectProvider<MeterRegistry> meterRegistry) {
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.processedEventRepository = processedEventRepository;
        this.billingRulesRepository = billingRulesRepository;
        this.partyRepository = partyRepository;
        MeterRegistry registry = meterRegistry.getIfAvailable();
        this.payloadRejectedCounter = registry == null
                ? null
                : Counter.builder("replica.payload.rejected")
                        .description(
                                "Replica event payloads rejected due to Jackson databind failures (e.g. omitted primitive fields)")
                        .tag("owner", "customer")
                        .tag("entity", "customer-events")
                        .register(registry);
    }

    @KafkaListener(
            topics = "${pos.accounting.kafka.customer-events-topic:customer.events.v1}",
            groupId = "pos-accounting-customer-events")
    @Transactional
    public void onCustomerEvent(@NonNull String message) {
        JsonNode envelope;
        try {
            envelope = objectMapper.readTree(message);
        } catch (Exception e) {
            log.warn("Skipping unparsable customer event: {}", message, e);
            return;
        }
        String eventType = envelope.path("eventType").stringValue(null);
        if (!isHandled(eventType)) {
            log.debug("Ignoring customer event type={}", eventType);
            return;
        }
        String eventId = envelope.path("eventId").stringValue(null);
        if (eventId == null || eventId.isBlank()) {
            log.warn("Skipping customer event without eventId: {}", message);
            return;
        }
        if (processedEventRepository.existsById(eventId)) {
            log.debug("Skipping duplicate customer event eventId={}", eventId);
            return;
        }

        try {
            switch (eventType) {
                case BillingRulesUpdatedV1.EVENT_TYPE -> applyBillingRulesUpdate(envelope);
                case CustomerPartyUpdatedV1.EVENT_TYPE -> applyPartyUpdated(envelope);
                case CustomerPartyDeletedV1.EVENT_TYPE -> applyPartyDeleted(envelope);
                default -> throw new IllegalStateException("Unhandled customer event type: " + eventType);
            }
        } catch (TransientDataAccessException e) {
            // Retry with backoff / DLQ via the container error handler (ADR-0044 §4).
            throw e;
        } catch (DatabindException e) {
            if (payloadRejectedCounter != null) {
                payloadRejectedCounter.increment();
            }
            log.error("Rejected malformed customer event payload eventId={}: {}", eventId, e.getMessage(), e);
        } catch (Exception e) {
            log.warn("Skipping malformed customer event eventId={}", eventId, e);
        }
        processedEventRepository.save(ProcessedEvent.builder()
                .eventId(eventId)
                .processedAt(Instant.now(clock))
                .build());
    }

    private void applyBillingRulesUpdate(JsonNode envelope) {
        BillingRulesUpdatedV1 payload = objectMapper.treeToValue(envelope.path("payload"), BillingRulesUpdatedV1.class);
        long aggregateVersion = envelope.path("aggregateVersion").longValue(0);
        UUID partyId = payload.partyId();

        ExtCustomerBillingRules existing =
                billingRulesRepository.findById(partyId).orElse(null);
        // Strictly-newer-only skip: equal versions APPLY (#1486, ReplicaVersionGuard) — the
        // party's aggregateVersion strictly advances, so equal means identical content, and a
        // future replay would resend the held version deliberately to repair a replica with wrong
        // or missing rows. The old `&& aggregateVersion > 0` carve-out let a legacy version-0
        // envelope always apply, no matter how far ahead the held replica already was. A version-0
        // fact is legitimate only for a brand-new party (a fresh @Version row starts at 0), and
        // then no replica row exists so this guard is never consulted; against a replica already
        // holding a higher version, a 0 can only be legacy/malformed, and letting the plain >
        // comparison treat it as stale is the safer behavior.
        if (existing != null && ReplicaVersionGuard.isStale(existing.getAggregateVersion(), aggregateVersion)) {
            log.debug(
                    "Skipping stale billing-rules event partyId={} eventVersion={} replicaVersion={}",
                    partyId,
                    aggregateVersion,
                    existing.getAggregateVersion());
            return;
        }

        billingRulesRepository.save(ExtCustomerBillingRules.builder()
                .partyId(partyId)
                .poRequired(payload.poRequired())
                .taxExempt(payload.taxExempt())
                .creditHold(payload.creditHold())
                .autoPayEnabled(payload.autoPayEnabled())
                .paymentTerms(payload.paymentTerms())
                .creditLimit(payload.creditLimit())
                .currency(payload.currency())
                .invoiceDeliveryMethod(payload.invoiceDeliveryMethod())
                .billingAddressId(payload.billingAddressId())
                .discountPolicyRef(payload.discountPolicyRef())
                .aggregateVersion(aggregateVersion)
                .updatedAt(Instant.now(clock))
                .build());
        log.info("Updated ext_customer_billing_rules replica partyId={} version={}", partyId, aggregateVersion);
    }

    /**
     * Types this consumer materializes. Everything else on {@code customer.events.v1} is another
     * consumer's concern and is skipped before the {@code processed_events} insert — unchanged
     * from the pre-#1779 behavior for the types this module still ignores.
     */
    private static boolean isHandled(String eventType) {
        return BillingRulesUpdatedV1.EVENT_TYPE.equals(eventType)
                || CustomerPartyUpdatedV1.EVENT_TYPE.equals(eventType)
                || CustomerPartyDeletedV1.EVENT_TYPE.equals(eventType);
    }

    /**
     * Materializes party identity into {@code ext_customer_party} (issue #1779), so accounting
     * responses can show a customer's name or number instead of the party UUID. Same
     * {@link ReplicaVersionGuard} semantics as the billing-rules replica above.
     */
    private void applyPartyUpdated(JsonNode envelope) {
        CustomerPartyUpdatedV1 payload =
                objectMapper.treeToValue(envelope.path("payload"), CustomerPartyUpdatedV1.class);
        long aggregateVersion = envelope.path("aggregateVersion").longValue(0);
        UUID partyId = payload.partyId();

        // party_type and status are NOT NULL in ext_customer_party, and this entity has an
        // assigned @Id with no @Version, so save() routes through merge() and the insert is
        // deferred to flush — a constraint violation would surface at commit, AFTER the
        // catch-and-skip below and after the processed_events row is written. The whole
        // transaction including the dedupe row would then roll back and the container would
        // redeliver the same offset forever. This class promises malformed payloads are skipped,
        // not that they block the partition, so reject the fact here where the skip still works.
        if (isBlank(payload.partyType()) || isBlank(payload.status())) {
            log.warn(
                    "Skipping customer-party event partyId={} with blank partyType/status; the replica requires both",
                    partyId);
            return;
        }

        ExtCustomerParty existing = partyRepository.findById(partyId).orElse(null);
        if (existing != null && ReplicaVersionGuard.isStale(existing.getAggregateVersion(), aggregateVersion)) {
            log.debug(
                    "Skipping stale customer-party event partyId={} eventVersion={} replicaVersion={}",
                    partyId,
                    aggregateVersion,
                    existing.getAggregateVersion());
            return;
        }

        partyRepository.save(ExtCustomerParty.builder()
                .partyId(partyId)
                .partyType(payload.partyType())
                .displayName(payload.displayName())
                .customerNumber(payload.customerNumber())
                .status(payload.status())
                .aggregateVersion(aggregateVersion)
                .updatedAt(Instant.now(clock))
                .build());
        log.info("Updated ext_customer_party replica partyId={} version={}", partyId, aggregateVersion);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * The owner deleted the party: drop the replica row. Accounting records referencing the party
     * are untouched — they keep their {@code customerId} and simply stop resolving a display
     * name, which is the correct outcome (a null display value, never a UUID substituted for it).
     */
    private void applyPartyDeleted(JsonNode envelope) {
        CustomerPartyDeletedV1 payload =
                objectMapper.treeToValue(envelope.path("payload"), CustomerPartyDeletedV1.class);
        partyRepository.deleteById(payload.partyId());
        log.info("Deleted ext_customer_party replica partyId={}", payload.partyId());
    }
}
