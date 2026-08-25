package com.positivity.order.internal.service;

import com.positivity.domainevents.ReplicaVersionGuard;
import com.positivity.domainevents.workorder.EstimateUpdatedV1;
import com.positivity.domainevents.workorder.WorkorderUpdatedV1;
import com.positivity.order.internal.entity.ExtEstimate;
import com.positivity.order.internal.entity.ExtEstimateLine;
import com.positivity.order.internal.entity.ExtWorkorder;
import com.positivity.order.internal.entity.ExtWorkorderLine;
import com.positivity.order.internal.entity.ProcessedEvent;
import com.positivity.order.internal.repository.ExtEstimateLineRepository;
import com.positivity.order.internal.repository.ExtEstimateRepository;
import com.positivity.order.internal.repository.ExtWorkorderLineRepository;
import com.positivity.order.internal.repository.ExtWorkorderRepository;
import com.positivity.order.internal.repository.ProcessedEventRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Consumes {@code workorder.events.v1} into the {@code ext_workorder(_line)} and
 * {@code ext_estimate(_line)} replicas (parity story E1, spec R7.1): each snapshot fact fully
 * replaces the document's line set, so the source-document import always reads the latest
 * approved prices. Same consumer contract as the other replica listeners: idempotent via
 * {@code processed_events}, stale envelopes skipped, transient DB errors rethrown, malformed
 * payloads logged and skipped.
 *
 * <p>The stale guard on {@code WorkorderUpdatedV1}'s and {@code EstimateUpdatedV1}'s
 * {@code aggregateVersion} is {@link ReplicaVersionGuard} (#1486): pos-workorder's {@code Workorder}
 * and {@code Estimate} each carry a JPA {@code @Version} that strictly advances, so a held row is
 * stale only when its version is strictly greater than the incoming fact's — an equal version
 * applies, both because it is an idempotent no-op for live traffic and because it is what would let
 * a future regenerate-from-state replay repair a replica that holds the version number but wrong or
 * missing rows.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "pos.order.kafka", name = "enabled", havingValue = "true")
public class WorkorderEventsListener {
    private static final String UNIT_PRICE = "unitPrice";

    private static final String QUANTITY = "quantity";

    private static final String LINE_TOTAL = "lineTotal";

    private static final String DESCRIPTION = "description";

    static final String OWNER = "workorder";

    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final ProcessedEventRepository processedEventRepository;
    private final ExtWorkorderRepository extWorkorderRepository;
    private final ExtWorkorderLineRepository extWorkorderLineRepository;
    private final ExtEstimateRepository extEstimateRepository;
    private final ExtEstimateLineRepository extEstimateLineRepository;

    @KafkaListener(
            topics = "${pos.order.kafka.workorder-events-topic:workorder.events.v1}",
            groupId = "${pos.order.kafka.workorder-events-consumer-group:pos-order-workorder-events}")
    @Transactional
    public void onWorkorderEvent(@NonNull String message) {
        JsonNode envelope;
        try {
            envelope = objectMapper.readTree(message);
        } catch (Exception e) {
            log.warn("Skipping unparsable workorder event: {}", message, e);
            return;
        }
        String eventType = envelope.path("eventType").stringValue(null);
        boolean workorder = WorkorderUpdatedV1.EVENT_TYPE.equals(eventType);
        boolean estimate = EstimateUpdatedV1.EVENT_TYPE.equals(eventType);
        if (!workorder && !estimate) {
            log.debug("Ignoring workorder event type={}", eventType);
            return;
        }
        String eventId = envelope.path("eventId").stringValue(null);
        if (eventId == null || eventId.isBlank()) {
            log.warn("Skipping workorder event without eventId");
            return;
        }
        if (processedEventRepository.existsById(eventId)) {
            log.debug("Skipping duplicate workorder event eventId={}", eventId);
            return;
        }

        try {
            if (workorder) {
                applyWorkorder(envelope);
            } else {
                applyEstimate(envelope);
            }
            processedEventRepository.save(ProcessedEvent.builder()
                    .eventId(eventId)
                    .owner(OWNER)
                    .processedAt(Instant.now(clock))
                    .build());
        } catch (TransientDataAccessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Skipping malformed workorder event eventId={}", eventId, e);
        }
    }

    private void applyWorkorder(JsonNode envelope) {
        JsonNode payload = envelope.path("payload");
        UUID workorderId = UUID.fromString(payload.path("workorderId").stringValue(null));
        long aggregateVersion = envelope.path("aggregateVersion").longValue(0L);

        ExtWorkorder existing = extWorkorderRepository.findById(workorderId).orElse(null);
        // Strictly-newer-only skip: equal versions APPLY (#1486, ReplicaVersionGuard) — workorder's
        // aggregateVersion strictly advances, so equal means identical content, and a future replay
        // would resend the held version deliberately to repair a replica with wrong or missing rows.
        if (existing != null && ReplicaVersionGuard.isStale(existing.getAggregateVersion(), aggregateVersion)) {
            log.debug("Skipping stale workorder event for {}", workorderId);
            return;
        }
        ExtWorkorder replica = existing != null ? existing : new ExtWorkorder();
        replica.setWorkorderId(workorderId);
        replica.setWorkorderNumber(payload.path("workorderNumber").stringValue(null));
        replica.setStatus(payload.path("status").stringValue(null));
        replica.setCustomerId(uuid(payload, "customerId"));
        replica.setVehicleId(uuid(payload, "vehicleId"));
        replica.setShopId(uuid(payload, "shopId"));
        replica.setInvoiceId(uuid(payload, "invoiceId"));
        replica.setAggregateVersion(aggregateVersion);
        replica.setSyncedAt(Instant.now(clock));
        extWorkorderRepository.save(replica);

        // Full replacement set on every snapshot.
        extWorkorderLineRepository.deleteByWorkorderId(workorderId);
        for (JsonNode part : payload.path("parts")) {
            extWorkorderLineRepository.save(ExtWorkorderLine.builder()
                    .lineId(UUID.fromString(part.path("workorderLineId").stringValue(null)))
                    .workorderId(workorderId)
                    .lineKind(ExtWorkorderLine.LineKind.PART)
                    .productId(uuid(part, "productEntityId"))
                    .description(part.path(DESCRIPTION).stringValue(null))
                    .quantity(decimal(part, QUANTITY))
                    .unitPrice(decimal(part, UNIT_PRICE))
                    .lineTotal(decimal(part, LINE_TOTAL))
                    .declined(bool(part, "declined"))
                    .returnable(bool(part, "returnable"))
                    .build());
        }
        for (JsonNode service : payload.path("services")) {
            extWorkorderLineRepository.save(ExtWorkorderLine.builder()
                    .lineId(UUID.fromString(service.path("workorderLineId").stringValue(null)))
                    .workorderId(workorderId)
                    .lineKind(ExtWorkorderLine.LineKind.SERVICE)
                    .description(service.path(DESCRIPTION).stringValue(null))
                    .quantity(decimal(service, QUANTITY))
                    .unitPrice(decimal(service, UNIT_PRICE))
                    .lineTotal(decimal(service, LINE_TOTAL))
                    .declined(bool(service, "declined"))
                    .build());
        }
    }

    private void applyEstimate(JsonNode envelope) {
        JsonNode payload = envelope.path("payload");
        UUID estimateId = UUID.fromString(payload.path("estimateId").stringValue(null));
        long aggregateVersion = envelope.path("aggregateVersion").longValue(0L);

        ExtEstimate existing = extEstimateRepository.findById(estimateId).orElse(null);
        // Strictly-newer-only skip: equal versions APPLY (#1486, ReplicaVersionGuard) — estimate's
        // aggregateVersion strictly advances, so equal means identical content, and a future replay
        // would resend the held version deliberately to repair a replica with wrong or missing rows.
        if (existing != null && ReplicaVersionGuard.isStale(existing.getAggregateVersion(), aggregateVersion)) {
            log.debug("Skipping stale estimate event for {}", estimateId);
            return;
        }
        ExtEstimate replica = existing != null ? existing : new ExtEstimate();
        replica.setEstimateId(estimateId);
        replica.setEstimateNumber(payload.path("estimateNumber").stringValue(null));
        replica.setStatus(payload.path("status").stringValue(null));
        replica.setCustomerId(uuid(payload, "customerId"));
        replica.setVehicleId(uuid(payload, "vehicleId"));
        replica.setLocationId(uuid(payload, "locationId"));
        replica.setAggregateVersion(aggregateVersion);
        replica.setSyncedAt(Instant.now(clock));
        extEstimateRepository.save(replica);

        extEstimateLineRepository.deleteByEstimateId(estimateId);
        for (JsonNode item : payload.path("items")) {
            extEstimateLineRepository.save(ExtEstimateLine.builder()
                    .itemId(UUID.fromString(item.path("estimateItemId").stringValue(null)))
                    .estimateId(estimateId)
                    .itemType(item.path("itemType").stringValue(null))
                    .description(item.path(DESCRIPTION).stringValue(null))
                    .quantity(decimal(item, QUANTITY))
                    .unitPrice(decimal(item, UNIT_PRICE))
                    .lineTotal(decimal(item, LINE_TOTAL))
                    .productId(uuid(item, "productId"))
                    .serviceId(uuid(item, "serviceId"))
                    .approvalStatus(item.path("approvalStatus").stringValue(null))
                    .deleted(bool(item, "deleted"))
                    .build());
        }
    }

    @Nullable
    private static UUID uuid(JsonNode node, String field) {
        String value = node.path(field).stringValue(null);
        return value == null || value.isBlank() ? null : UUID.fromString(value);
    }

    @Nullable
    private static BigDecimal decimal(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.decimalValue();
    }

    @Nullable
    private static Boolean bool(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.booleanValue(false);
    }
}
