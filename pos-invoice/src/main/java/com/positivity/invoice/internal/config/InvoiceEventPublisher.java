package com.positivity.invoice.internal.config;

import com.positivity.domainevents.DomainEventEnvelope;
import com.positivity.domainevents.DomainTopics;
import com.positivity.domainevents.invoice.InvoiceUpdatedV1;
import com.positivity.invoice.internal.entity.Invoice;
import java.time.Clock;
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
    private final ObjectProvider<OutboxEventWriter> outboxEventWriter;

    public void publishInvoiceUpdated(@NonNull Invoice invoice) {
        OutboxEventWriter writer = outboxEventWriter.getIfAvailable();
        if (writer == null) {
            return;
        }
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
                // Pre-flush JPA @Version: lags the committed value by one but stays monotonic,
                // which is all the consumer's stale-event check needs.
                invoice.getVersion() == null ? 0L : invoice.getVersion().longValue(),
                "pos-invoice",
                null,
                null,
                payload,
                clock);
        writer.publish(DomainTopics.events("invoice"), envelope);
        log.debug("Queued invoice.invoice.updated invoiceId={} status={}", invoice.getId(), invoice.getStatus());
    }
}
