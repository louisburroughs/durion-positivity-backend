package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.entity.ExtCustomerBillingRules;
import com.positivity.accounting.internal.entity.ProcessedEvent;
import com.positivity.accounting.internal.repository.ExtCustomerBillingRulesRepository;
import com.positivity.accounting.internal.repository.ProcessedEventRepository;
import com.positivity.domainevents.customer.BillingRulesUpdatedV1;
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
 * <p>Currently materializes {@code customer.billing-rules.updated} into
 * {@code ext_customer_billing_rules}; other event types are ignored. Idempotent via
 * {@code processed_events} (same transaction as the replica upsert), and stale events (envelope
 * {@code aggregateVersion} at or below the replica's) are skipped so out-of-order redelivery
 * cannot regress the replica. Transient DB errors propagate to the container error handler for
 * retry/DLQ; malformed payloads are logged and skipped (poison messages must not block the
 * partition).
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "pos.accounting.kafka", name = "enabled", havingValue = "true")
public class CustomerEventsListener {

    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final ProcessedEventRepository processedEventRepository;
    private final ExtCustomerBillingRulesRepository billingRulesRepository;
    private final Counter payloadRejectedCounter;

    public CustomerEventsListener(
            Clock clock,
            ObjectMapper objectMapper,
            ProcessedEventRepository processedEventRepository,
            ExtCustomerBillingRulesRepository billingRulesRepository,
            ObjectProvider<MeterRegistry> meterRegistry) {
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.processedEventRepository = processedEventRepository;
        this.billingRulesRepository = billingRulesRepository;
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
        if (!BillingRulesUpdatedV1.EVENT_TYPE.equals(eventType)) {
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
            applyBillingRulesUpdate(envelope);
        } catch (TransientDataAccessException e) {
            // Retry with backoff / DLQ via the container error handler (ADR-0044 §4).
            throw e;
        } catch (DatabindException e) {
            if (payloadRejectedCounter != null) {
                payloadRejectedCounter.increment();
            }
            log.error("Rejected malformed billing-rules event payload eventId={}: {}", eventId, e.getMessage(), e);
        } catch (Exception e) {
            log.warn("Skipping malformed billing-rules event eventId={}", eventId, e);
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
        if (existing != null && existing.getAggregateVersion() >= aggregateVersion && aggregateVersion > 0) {
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
}
