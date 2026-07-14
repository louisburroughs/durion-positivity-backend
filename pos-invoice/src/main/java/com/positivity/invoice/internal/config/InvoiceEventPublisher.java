package com.positivity.invoice.internal.config;

import com.positivity.domainevents.DomainEventEnvelope;
import com.positivity.domainevents.DomainTopics;
import com.positivity.domainevents.invoice.BillingRulesUpdatedV1;
import com.positivity.domainevents.invoice.InvoiceUpdatedV1;
import com.positivity.invoice.internal.entity.BillingRules;
import com.positivity.invoice.internal.entity.Invoice;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Emits {@code invoice.invoice.updated} to the invoice outbox after document mutations
 * (ADR-0044, #842).
 *
 * <p>No-op when the Kafka feature flag ({@code pos.invoice.kafka.enabled}) is off — the
 * {@link OutboxEventWriter} bean is conditional, so this publisher degrades gracefully. Must be
 * called inside the mutating transaction (the writer requires {@code MANDATORY} propagation).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InvoiceEventPublisher {

    private final Clock clock;
    private final EntityManager entityManager;
    private final ObjectProvider<OutboxEventWriter> outboxEventWriter;

    public void publishInvoiceUpdated(@NonNull Invoice invoice) {
        OutboxEventWriter writer = outboxEventWriter.getIfAvailable();
        if (writer == null) {
            return;
        }
        // Flush so the JPA @Version already carries the committed value: versions are then
        // strictly increasing per invoice (create=0, updates=1,2,...), which the consumer's
        // stale-event guard relies on (PR #850 review).
        entityManager.flush();
        InvoiceUpdatedV1 payload = new InvoiceUpdatedV1(
                invoice.getId(),
                invoice.getInvoiceNumber(),
                invoice.getWorkorderId(),
                invoice.getEstimateId(),
                invoice.getLocationId(),
                invoice.getPartyId(),
                invoice.getStatus().name(),
                invoice.getSubtotal(),
                invoice.getTax(),
                invoice.getTotal(),
                invoice.getAdjustmentsAmount(),
                invoice.getCreatedAt(),
                invoice.getFinalizedAt());
        DomainEventEnvelope<InvoiceUpdatedV1> envelope = DomainEventEnvelope.of(
                InvoiceUpdatedV1.EVENT_TYPE,
                InvoiceUpdatedV1.SCHEMA_VERSION,
                invoice.getId(),
                invoice.getVersion() == null ? 0L : invoice.getVersion().longValue(),
                "pos-invoice",
                null,
                null,
                payload,
                clock);
        writer.publish(DomainTopics.events("invoice"), envelope);
        log.debug("Queued invoice.invoice.updated invoiceId={} status={}", invoice.getId(), invoice.getStatus());
    }

    /** Emits {@code invoice.billing-rules.updated} after a rules mutation (ADR-0044, #902). */
    public void publishBillingRulesUpdated(@NonNull BillingRules rules) {
        OutboxEventWriter writer = outboxEventWriter.getIfAvailable();
        if (writer == null) {
            return;
        }
        // Same flush rationale as invoices: the JPA @Version must carry the committed value so
        // the consumer's stale-event guard sees strictly increasing versions per party.
        entityManager.flush();
        BillingRulesUpdatedV1 payload = new BillingRulesUpdatedV1(
                rules.getPartyId(),
                rules.isPurchaseOrderRequired(),
                rules.getPaymentTermsCode(),
                rules.getInvoiceDeliveryMethod() == null
                        ? null
                        : rules.getInvoiceDeliveryMethod().name(),
                rules.getInvoiceGroupingStrategy() == null
                        ? null
                        : rules.getInvoiceGroupingStrategy().name());
        DomainEventEnvelope<BillingRulesUpdatedV1> envelope = DomainEventEnvelope.of(
                BillingRulesUpdatedV1.EVENT_TYPE,
                BillingRulesUpdatedV1.SCHEMA_VERSION,
                billingRulesAggregateId(rules.getPartyId()),
                rules.getVersion() == null ? 0L : rules.getVersion().longValue(),
                "pos-invoice",
                null,
                null,
                payload,
                clock);
        writer.publish(DomainTopics.events("invoice"), envelope);
        log.debug(
                "Queued invoice.billing-rules.updated partyId(hash)={}",
                rules.getPartyId().hashCode());
    }

    /** partyId is stored as a 36-char string; parse when it is a UUID, else derive one stably. */
    private static UUID billingRulesAggregateId(@NonNull String partyId) {
        try {
            return UUID.fromString(partyId);
        } catch (IllegalArgumentException _) {
            return UUID.nameUUIDFromBytes(partyId.getBytes(StandardCharsets.UTF_8));
        }
    }
}
