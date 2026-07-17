package com.positivity.inventory.internal.service;

import com.positivity.domainevents.warranty.WarrantyPartReturnRequestedV1;
import com.positivity.domainevents.warranty.WarrantyPartReturnShippedV1;
import com.positivity.inventory.internal.entity.ProcessedEvent;
import com.positivity.inventory.internal.entity.WarrantyPartReturnHold;
import com.positivity.inventory.internal.repository.ProcessedEventRepository;
import com.positivity.inventory.internal.repository.WarrantyPartReturnHoldRepository;
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
 * Consumes {@code warranty.events.v1} into the {@code warranty_part_return_hold} quarantine
 * tracking records (issue #927).
 *
 * <p>Same consumer contract as {@link WorkorderEventsListener}: {@code processed_events}
 * idempotency in the apply transaction, stale guard on the envelope {@code aggregateVersion},
 * transient DB errors rethrown for container retry/DLQ, malformed payloads logged and skipped.
 * The topic also carries reimbursement and claim-lifecycle facts this module ignores — their
 * eventIds are still recorded under the {@code warranty} owner.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "pos.inventory.kafka", name = "enabled", havingValue = "true")
public class WarrantyEventsListener {

    static final String OWNER = "warranty";

    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final ProcessedEventRepository processedEventRepository;
    private final WarrantyPartReturnHoldRepository partReturnHoldRepository;

    @KafkaListener(
            topics = "${pos.inventory.kafka.warranty-events-topic:warranty.events.v1}",
            groupId = "${pos.inventory.kafka.warranty-events-consumer-group:pos-inventory-warranty-events}")
    @Transactional
    public void onWarrantyEvent(@NonNull String message) {
        JsonNode envelope;
        try {
            envelope = objectMapper.readTree(message);
        } catch (Exception e) {
            log.warn("Skipping unparsable warranty event: {}", message, e);
            return;
        }
        String eventType = envelope.path("eventType").stringValue(null);
        String eventId = envelope.path("eventId").stringValue(null);
        if (eventId == null || eventId.isBlank()) {
            log.warn("Skipping warranty event without eventId: {}", message);
            return;
        }
        if (processedEventRepository.existsById(eventId)) {
            return;
        }

        try {
            if (WarrantyPartReturnRequestedV1.EVENT_TYPE.equals(eventType)) {
                applyPartReturnRequested(envelope);
            } else if (WarrantyPartReturnShippedV1.EVENT_TYPE.equals(eventType)) {
                applyPartReturnShipped(envelope);
            } else {
                // Ignored types still fall through to the processed_events insert below.
                log.debug("Ignoring warranty event type={} eventId={}", eventType, eventId);
            }
        } catch (TransientDataAccessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Skipping malformed warranty event eventId={}", eventId, e);
        }
        processedEventRepository.save(ProcessedEvent.builder()
                .eventId(eventId)
                .owner(OWNER)
                .processedAt(Instant.now(clock))
                .build());
    }

    private void applyPartReturnRequested(JsonNode envelope) {
        WarrantyPartReturnRequestedV1 payload =
                objectMapper.treeToValue(envelope.path("payload"), WarrantyPartReturnRequestedV1.class);
        long aggregateVersion = envelope.path("aggregateVersion").longValue(0);

        WarrantyPartReturnHold existing =
                partReturnHoldRepository.findById(payload.partReturnId()).orElse(null);
        if (existing != null && existing.getAggregateVersion() > aggregateVersion) {
            return;
        }

        // Preserve shipment data if the shipped fact arrived first.
        WarrantyPartReturnHold.WarrantyPartReturnHoldBuilder builder = existing != null
                ? existing.toBuilder()
                : WarrantyPartReturnHold.builder().status(WarrantyPartReturnHold.STATUS_REQUESTED);
        partReturnHoldRepository.save(builder.partReturnId(payload.partReturnId())
                .claimId(payload.claimId())
                .claimLineId(payload.claimLineId())
                .productEntityId(payload.productEntityId())
                .serialNumber(payload.serialNumber())
                .disposition(payload.disposition())
                .requestedAt(payload.requestedAt())
                .aggregateVersion(aggregateVersion)
                .updatedAt(Instant.now(clock))
                .build());
        log.info(
                "Recorded warranty part-return hold partReturnId={} disposition={} version={}",
                payload.partReturnId(),
                payload.disposition(),
                aggregateVersion);
    }

    private void applyPartReturnShipped(JsonNode envelope) {
        WarrantyPartReturnShippedV1 payload =
                objectMapper.treeToValue(envelope.path("payload"), WarrantyPartReturnShippedV1.class);
        long aggregateVersion = envelope.path("aggregateVersion").longValue(0);

        WarrantyPartReturnHold existing =
                partReturnHoldRepository.findById(payload.partReturnId()).orElse(null);
        if (existing != null && existing.getAggregateVersion() > aggregateVersion) {
            return;
        }

        // Upsert-tolerant: if the requested fact was missed, create the row from what the
        // shipment carries (disposition/requested_at stay null — pos-warranty remains the
        // system of record for the full lifecycle).
        WarrantyPartReturnHold.WarrantyPartReturnHoldBuilder builder = existing != null
                ? existing.toBuilder()
                : WarrantyPartReturnHold.builder()
                        .partReturnId(payload.partReturnId())
                        .claimId(payload.claimId())
                        .claimLineId(payload.claimLineId());
        partReturnHoldRepository.save(builder.status(WarrantyPartReturnHold.STATUS_SHIPPED)
                .carrier(payload.carrier())
                .trackingNumber(payload.trackingNumber())
                .shippedAt(payload.shippedAt())
                .aggregateVersion(aggregateVersion)
                .updatedAt(Instant.now(clock))
                .build());
        log.info(
                "Marked warranty part-return hold shipped partReturnId={} trackingNumber={} version={}",
                payload.partReturnId(),
                payload.trackingNumber(),
                aggregateVersion);
    }
}
