package com.positivity.workorder.internal.service;

import com.positivity.domainevents.invoice.BillingRulesUpdatedV1;
import com.positivity.domainevents.invoice.InvoiceUpdatedV1;
import com.positivity.workorder.internal.entity.ExtBillingRulesReplica;
import com.positivity.workorder.internal.entity.ExtInvoiceReplica;
import com.positivity.workorder.internal.entity.ProcessedEvent;
import com.positivity.workorder.internal.entity.Workorder;
import com.positivity.workorder.internal.repository.ExtBillingRulesReplicaRepository;
import com.positivity.workorder.internal.repository.ExtInvoiceReplicaRepository;
import com.positivity.workorder.internal.repository.ProcessedEventRepository;
import com.positivity.workorder.internal.repository.WorkorderRepository;
import java.time.Clock;
import java.time.Instant;
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
 * Consumes {@code invoice.events.v1} into the {@code ext_invoice} replica (ADR-0044 §6, #900)
 * and resolves the async invoice-generation pending state: when a fact carries a workorderId
 * whose workorder has no linked invoice yet, {@code workorder.invoiceId} is set here — the
 * replacement for the retired synchronous {@code InvoiceClient} response handling.
 *
 * <p>Phase 3.4 consumer contract: {@code processed_events} idempotency (owner {@code invoice})
 * in the apply transaction, strictly-below stale guard on the fact's JPA-version
 * {@code aggregateVersion}, transient DB errors rethrown for container retry/DLQ. Unsupported
 * event types still record their eventIds so the owner's manifest reconciles.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "workorder.kafka", name = "enabled", havingValue = "true")
public class InvoiceEventsListener {

    static final String OWNER = "invoice";

    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final ProcessedEventRepository processedEventRepository;
    private final ExtInvoiceReplicaRepository extInvoiceReplicaRepository;
    private final ExtBillingRulesReplicaRepository extBillingRulesReplicaRepository;
    private final WorkorderRepository workorderRepository;
    private final WorkorderFactPublisher workorderFactPublisher;

    @KafkaListener(
            topics = "${workorder.kafka.invoice-events-topic:invoice.events.v1}",
            groupId = "${workorder.kafka.invoice-events-consumer-group:pos-workorder-invoice-events}")
    @Transactional
    public void onInvoiceEvent(@NonNull String message) {
        JsonNode envelope;
        try {
            envelope = objectMapper.readTree(message);
        } catch (Exception e) {
            log.warn("Skipping unparsable invoice event: {}", message, e);
            return;
        }
        String eventType = envelope.path("eventType").stringValue(null);
        String eventId = envelope.path("eventId").stringValue(null);
        if (eventId == null || eventId.isBlank()) {
            log.warn("Skipping invoice event without eventId: {}", message);
            return;
        }
        if (processedEventRepository.existsById(eventId)) {
            return;
        }

        try {
            if (InvoiceUpdatedV1.EVENT_TYPE.equals(eventType)) {
                applyInvoiceUpdated(envelope);
            } else if (BillingRulesUpdatedV1.EVENT_TYPE.equals(eventType)) {
                applyBillingRulesUpdated(envelope);
            } else {
                // Ignored types still fall through to the processed_events insert below: the
                // owner's manifest counts every fact in the window.
                log.debug("Ignoring invoice event type={} eventId={}", eventType, eventId);
            }
        } catch (TransientDataAccessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Skipping malformed invoice event eventId={}", eventId, e);
        }
        processedEventRepository.save(ProcessedEvent.builder()
                .eventId(eventId)
                .owner(OWNER)
                .processedAt(Instant.now(clock))
                .build());
    }

    private void applyBillingRulesUpdated(JsonNode envelope) {
        BillingRulesUpdatedV1 payload = objectMapper.treeToValue(envelope.path("payload"), BillingRulesUpdatedV1.class);
        long aggregateVersion = envelope.path("aggregateVersion").longValue(0);
        ExtBillingRulesReplica existing =
                extBillingRulesReplicaRepository.findById(payload.partyId()).orElse(null);
        if (existing != null && existing.getAggregateVersion() > aggregateVersion) {
            return;
        }
        extBillingRulesReplicaRepository.save(ExtBillingRulesReplica.builder()
                .partyId(payload.partyId())
                .purchaseOrderRequired(payload.purchaseOrderRequired())
                .paymentTermsCode(payload.paymentTermsCode())
                .invoiceDeliveryMethod(payload.invoiceDeliveryMethod())
                .invoiceGroupingStrategy(payload.invoiceGroupingStrategy())
                .aggregateVersion(aggregateVersion)
                .updatedAt(Instant.now(clock))
                .build());
        // partyId is logged as a hash, matching the owner's masking of party identifiers.
        log.info(
                "Updated ext_billing_rules partyId(hash)={} version={}",
                payload.partyId().hashCode(),
                aggregateVersion);
    }

    private void applyInvoiceUpdated(JsonNode envelope) {
        InvoiceUpdatedV1 payload = objectMapper.treeToValue(envelope.path("payload"), InvoiceUpdatedV1.class);
        long aggregateVersion = envelope.path("aggregateVersion").longValue(0);
        ExtInvoiceReplica existing =
                extInvoiceReplicaRepository.findById(payload.invoiceId()).orElse(null);
        if (existing != null && existing.getAggregateVersion() > aggregateVersion) {
            return;
        }
        extInvoiceReplicaRepository.save(ExtInvoiceReplica.builder()
                .invoiceId(payload.invoiceId())
                .workorderId(payload.workorderId())
                .invoiceNumber(payload.invoiceNumber())
                .status(payload.status())
                .subtotal(payload.subtotal())
                .tax(payload.tax())
                .total(payload.total())
                .invoiceCreatedAt(payload.createdAt())
                .aggregateVersion(aggregateVersion)
                .updatedAt(Instant.now(clock))
                .build());

        // Resolve the async generation pending state: first fact for a workorder links it.
        Workorder workorder =
                workorderRepository.findById(payload.workorderId()).orElse(null);
        if (workorder != null && workorder.getInvoiceId() == null) {
            workorder.setInvoiceId(payload.invoiceId());
            workorderRepository.save(workorder);
            workorderFactPublisher.markChanged(workorder.getId());
            log.info("Linked invoice {} to workorder {}", payload.invoiceId(), payload.workorderId());
        }
        log.info("Updated ext_invoice invoiceId={} version={}", payload.invoiceId(), aggregateVersion);
    }
}
