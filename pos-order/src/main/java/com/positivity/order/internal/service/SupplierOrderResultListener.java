package com.positivity.order.internal.service;

import com.positivity.domainevents.supplier.SupplierOrderConfirmedV1;
import com.positivity.domainevents.supplier.SupplierOrderRejectedV1;
import com.positivity.domainevents.supplier.SupplierOrderReviewRequiredV1;
import com.positivity.domainevents.supplier.SupplierOrderStatusChangedV1;
import com.positivity.domainevents.supplier.SupplierOrderStatusDespatch;
import com.positivity.domainevents.supplier.SupplierOrderStatusLine;
import com.positivity.domainevents.supplier.SupplierOrderStatusSchedule;
import com.positivity.order.internal.entity.ProcessedEvent;
import com.positivity.order.internal.entity.PurchaseOrderEntity;
import com.positivity.order.internal.entity.PurchaseOrderTransmissionEvent;
import com.positivity.order.internal.enums.TransmissionState;
import com.positivity.order.internal.repository.ProcessedEventRepository;
import com.positivity.order.internal.repository.PurchaseOrderRepository;
import com.positivity.order.internal.repository.PurchaseOrderTransmissionEventRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.Optional;
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
 * Hears what the vendor said about a purchase order (CAP-320 #1330, ADR-0044 §4).
 *
 * <h2>Why the order records this and not only the transmission log</h2>
 *
 * A buyer chasing a delivery looks at the order. Leaving the vendor's answer in pos-supplier's
 * intent ledger would mean the only place the answer exists is a place the buyer has no reason to
 * open, so the order carries its own state and its own timeline.
 *
 * <h2>Late answers must not undo settled ones</h2>
 *
 * Status observations are polled, so they arrive out of order more often than not — a slow reply
 * about an earlier observation can land after a newer one. The order's current state therefore
 * moves only on an observation newer than the one already applied, while the timeline keeps every
 * observation and orders it by the vendor's clock. "Where is it?" stays correct and "what happened
 * to it?" stays complete, which one field alone cannot do.
 *
 * <p>Confirmations and rejections are outcomes rather than observations and are applied whenever
 * they arrive: a vendor that has decided has decided, and a status poll from before that decision
 * must not overwrite it.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "pos.order.kafka", name = "enabled", havingValue = "true")
public class SupplierOrderResultListener {
    private static final String PAYLOAD = "payload";

    /** Producing domain, per the repo-wide {@code processed_events} convention. */
    static final String OWNER = "supplier";

    private final java.time.Clock clock;
    private final ObjectMapper objectMapper;
    private final ProcessedEventRepository processedEventRepository;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final PurchaseOrderTransmissionEventRepository transmissionEventRepository;

    @KafkaListener(
            topics = "${pos.order.kafka.supplier-events-topic:supplier.events.v1}",
            groupId = "${pos.order.kafka.supplier-events-consumer-group:pos-order-supplier-events}")
    @Transactional
    public void onSupplierEvent(@NonNull String message) {
        JsonNode envelope;
        try {
            envelope = objectMapper.readTree(message);
        } catch (Exception e) {
            log.warn("Skipping unparsable supplier event", e);
            return;
        }
        String eventType = envelope.path("eventType").stringValue(null);
        String eventId = envelope.path("eventId").stringValue(null);
        if (eventId == null || eventId.isBlank()) {
            log.warn("Skipping supplier event without eventId");
            return;
        }
        if (processedEventRepository.existsById(eventId)) {
            return;
        }

        try {
            if (SupplierOrderConfirmedV1.EVENT_TYPE.equals(eventType)) {
                applyConfirmed(envelope);
            } else if (SupplierOrderRejectedV1.EVENT_TYPE.equals(eventType)) {
                applyRejected(envelope);
            } else if (SupplierOrderStatusChangedV1.EVENT_TYPE.equals(eventType)) {
                applyStatusChanged(envelope);
            } else if (SupplierOrderReviewRequiredV1.EVENT_TYPE.equals(eventType)) {
                applyReviewRequired(envelope);
            } else {
                log.debug("Ignoring supplier event type={} eventId={}", eventType, eventId);
            }
        } catch (TransientDataAccessException e) {
            // Rethrown so the container retries. Recording this as processed would leave the buyer
            // looking at an order that says it is still waiting for an answer the vendor has
            // already given, with nothing left to correct it.
            throw e;
        } catch (Exception e) {
            log.warn("Skipping malformed supplier event eventId={}", eventId, e);
        }

        processedEventRepository.save(ProcessedEvent.builder()
                .eventId(eventId)
                .owner(OWNER)
                .processedAt(Instant.now(clock))
                .build());
    }

    private void applyConfirmed(JsonNode envelope) {
        SupplierOrderConfirmedV1 fact =
                objectMapper.treeToValue(envelope.path(PAYLOAD), SupplierOrderConfirmedV1.class);
        PurchaseOrderEntity order = orderFor(fact.purchaseOrderId());
        if (order == null) {
            return;
        }

        order.setTransmissionState(TransmissionState.CONFIRMED);
        order.setTransmissionIntentId(fact.transmissionIntentId());
        order.setVendorDocumentId(fact.documentId());
        order.setSupplierOrderNumber(fact.supplierOrderNumber());
        order.setTransmissionRejectionReason(null);
        order.setTransmissionVendorReason(null);
        order.setTransmissionObservedAt(fact.confirmedAt());
        purchaseOrderRepository.save(order);

        record(
                fact.purchaseOrderId(),
                fact.transmissionIntentId(),
                "CONFIRMED",
                null,
                fact.documentId(),
                fact.supplierOrderNumber(),
                null,
                null,
                null,
                fact.confirmedAt());

        log.info(
                "Purchase order {} confirmed by {} as {}",
                fact.purchaseOrderId(),
                fact.supplierRef(),
                fact.supplierOrderNumber());
    }

    private void applyRejected(JsonNode envelope) {
        SupplierOrderRejectedV1 fact = objectMapper.treeToValue(envelope.path(PAYLOAD), SupplierOrderRejectedV1.class);
        PurchaseOrderEntity order = orderFor(fact.purchaseOrderId());
        if (order == null) {
            return;
        }

        order.setTransmissionState(TransmissionState.REJECTED);
        order.setTransmissionIntentId(fact.transmissionIntentId());
        order.setVendorDocumentId(fact.documentId());
        order.setTransmissionRejectionReason(fact.reason().name());
        order.setTransmissionVendorReason(fact.vendorReason());
        order.setTransmissionObservedAt(fact.rejectedAt());
        purchaseOrderRepository.save(order);

        record(
                fact.purchaseOrderId(),
                fact.transmissionIntentId(),
                "REJECTED",
                fact.reason().name(),
                fact.documentId(),
                null,
                fact.vendorReason(),
                null,
                null,
                fact.rejectedAt());

        // Deliberately not re-sent. Getting the goods means a fresh request from this domain, which
        // pos-supplier mints as a new intent with a new document id — a re-send here would be the
        // blind retry ADR-0052 exists to make impossible.
        log.info("Purchase order {} rejected by {}: {}", fact.purchaseOrderId(), fact.supplierRef(), fact.reason());
    }

    /**
     * The transmission has stopped and needs a person.
     *
     * <p>Recorded because a buyer waiting on goods otherwise sees a request that looks in flight
     * and is indistinguishable from one genuinely in progress. This is not an outcome — nobody
     * knows yet whether the vendor has the order — so the order is not moved toward confirmed or
     * rejected, only marked as stopped.
     */
    private void applyReviewRequired(JsonNode envelope) {
        SupplierOrderReviewRequiredV1 fact =
                objectMapper.treeToValue(envelope.path(PAYLOAD), SupplierOrderReviewRequiredV1.class);
        PurchaseOrderEntity order = orderFor(fact.purchaseOrderId());
        if (order == null) {
            return;
        }

        // An order the vendor has already answered is not dragged back into limbo by a late
        // escalation about an earlier attempt.
        if (order.getTransmissionState() == TransmissionState.CONFIRMED
                || order.getTransmissionState() == TransmissionState.REJECTED) {
            log.debug("Ignoring review-required for {}: already answered", fact.purchaseOrderId());
            return;
        }

        order.setTransmissionState(TransmissionState.MANUAL_REVIEW);
        order.setTransmissionIntentId(fact.transmissionIntentId());
        order.setVendorDocumentId(fact.documentId());
        order.setTransmissionVendorReason(fact.detail());
        order.setTransmissionObservedAt(fact.escalatedAt());
        purchaseOrderRepository.save(order);

        record(
                fact.purchaseOrderId(),
                fact.transmissionIntentId(),
                "REVIEW_REQUIRED",
                null,
                fact.documentId(),
                null,
                fact.detail(),
                null,
                null,
                fact.escalatedAt());

        log.warn(
                "Purchase order {} transmission stopped pending review (document {}): {}",
                fact.purchaseOrderId(),
                fact.documentId(),
                fact.detail());
    }

    private void applyStatusChanged(JsonNode envelope) {
        SupplierOrderStatusChangedV1 fact =
                objectMapper.treeToValue(envelope.path(PAYLOAD), SupplierOrderStatusChangedV1.class);
        PurchaseOrderEntity order = orderFor(fact.purchaseOrderId());
        if (order == null) {
            return;
        }

        // Every observation joins the timeline, however late. It is the order's *current* state
        // that must not go backwards.
        record(
                fact.purchaseOrderId(),
                fact.transmissionIntentId(),
                "STATUS_CHANGED",
                fact.status().name(),
                fact.documentId(),
                fact.supplierOrderNumber(),
                fact.vendorReason(),
                earliestDespatchDate(fact),
                earliestDeliveryDate(fact),
                fact.observedAt());

        if (isStale(order, fact.observedAt())) {
            log.debug(
                    "Status observation for {} at {} is older than the applied {}; timeline only",
                    fact.purchaseOrderId(),
                    fact.observedAt(),
                    order.getTransmissionObservedAt());
            return;
        }
        // An outcome already reached is not revisited by a poll. A vendor that has confirmed or
        // refused has decided, and a status observation is a weaker statement than a decision.
        if (order.getTransmissionState() == TransmissionState.CONFIRMED
                || order.getTransmissionState() == TransmissionState.REJECTED) {
            order.setTransmissionObservedAt(fact.observedAt());
            order.setSupplierOrderNumber(
                    fact.supplierOrderNumber() == null ? order.getSupplierOrderNumber() : fact.supplierOrderNumber());
            purchaseOrderRepository.save(order);
            return;
        }

        order.setTransmissionIntentId(fact.transmissionIntentId());
        order.setVendorDocumentId(fact.documentId());
        order.setSupplierOrderNumber(fact.supplierOrderNumber());
        order.setTransmissionObservedAt(fact.observedAt());
        order.setTransmissionState(stateFor(fact.status(), order.getTransmissionState()));
        purchaseOrderRepository.save(order);
    }

    /**
     * The order's state after a vendor status observation.
     *
     * <p>{@code NOT_FOUND} deliberately does not move the order out of {@code REQUESTED}. The
     * vendor saying it has never heard of the document is exactly the ambiguity ADR-0052 refuses
     * to resolve automatically — it may mean the send never landed, or that the vendor has not
     * caught up. Treating it as a refusal would invite a re-order for goods that may already be
     * on their way.
     */
    private static TransmissionState stateFor(SupplierOrderStatusChangedV1.Status status, TransmissionState current) {
        return switch (status) {
            case CONFIRMED -> TransmissionState.CONFIRMED;
            case REJECTED -> TransmissionState.REJECTED;
            case IN_PROGRESS, NOT_FOUND -> current;
        };
    }

    private boolean isStale(PurchaseOrderEntity order, Instant observedAt) {
        return order.getTransmissionObservedAt() != null
                && observedAt != null
                && observedAt.isBefore(order.getTransmissionObservedAt());
    }

    /** The soonest despatch the vendor has stated across the order's lines, if any. */
    private static @Nullable LocalDate earliestDespatchDate(SupplierOrderStatusChangedV1 fact) {
        return fact.lines().stream()
                .map(SupplierOrderStatusLine::schedules)
                .flatMap(java.util.List::stream)
                .map(SupplierOrderStatusSchedule::despatches)
                .flatMap(java.util.List::stream)
                .map(SupplierOrderStatusDespatch::despatchDate)
                .filter(java.util.Objects::nonNull)
                .min(Comparator.naturalOrder())
                .orElse(null);
    }

    /** The soonest delivery the vendor has scheduled across the order's lines, if any. */
    private static @Nullable LocalDate earliestDeliveryDate(SupplierOrderStatusChangedV1 fact) {
        return fact.lines().stream()
                .map(SupplierOrderStatusLine::schedules)
                .flatMap(java.util.List::stream)
                .map(SupplierOrderStatusSchedule::deliveryDate)
                .filter(java.util.Objects::nonNull)
                .min(Comparator.naturalOrder())
                .orElse(null);
    }

    private @Nullable PurchaseOrderEntity orderFor(UUID purchaseOrderId) {
        Optional<PurchaseOrderEntity> order = purchaseOrderRepository.findById(purchaseOrderId);
        if (order.isEmpty()) {
            // pos-supplier only ever answers a command this domain sent, so an unknown order means
            // a data problem rather than a race worth retrying.
            log.warn("Supplier result references unknown purchase order {}", purchaseOrderId);
        }
        return order.orElse(null);
    }

    @SuppressWarnings("java:S107")
    private void record(
            UUID purchaseOrderId,
            UUID intentId,
            String eventType,
            String status,
            String documentId,
            String supplierOrderNumber,
            String vendorReason,
            LocalDate despatchDate,
            LocalDate estimatedDeliveryDate,
            Instant observedAt) {
        transmissionEventRepository.save(PurchaseOrderTransmissionEvent.builder()
                .purchaseOrderId(purchaseOrderId)
                .transmissionIntentId(intentId)
                .eventType(eventType)
                .status(status)
                .vendorDocumentId(documentId)
                .supplierOrderNumber(supplierOrderNumber)
                .vendorReason(vendorReason)
                .despatchDate(despatchDate)
                .estimatedDeliveryDate(estimatedDeliveryDate)
                .observedAt(observedAt == null ? Instant.now(clock) : observedAt)
                .recordedAt(Instant.now(clock))
                .build());
    }
}
