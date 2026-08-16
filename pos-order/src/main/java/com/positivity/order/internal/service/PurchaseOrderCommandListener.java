package com.positivity.order.internal.service;

import com.positivity.domainevents.order.PurchaseOrderRequestedLine;
import com.positivity.domainevents.order.PurchaseOrderRequestedV1;
import com.positivity.order.internal.dto.purchaseorder.CreatePurchaseOrderRequest;
import com.positivity.order.internal.dto.purchaseorder.PurchaseOrderLineRequest;
import com.positivity.order.internal.entity.ProcessedEvent;
import com.positivity.order.internal.repository.ProcessedEventRepository;
import com.positivity.order.internal.repository.PurchaseOrderRepository;
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
 * Places purchase orders that other domains ask for, on {@code order.commands.v1}
 * (CAP-320 #1334, ADR-0044 R1).
 *
 * <h2>Exactly one order per request</h2>
 *
 * The requester mints the order's identity, so the guarantee is a uniqueness constraint rather
 * than an application check: this listener refuses to create an order whose id already exists.
 * That makes a redelivered command, a retried publish and a double-submitted conversion all
 * indistinguishable from each other and all harmless — which matters because the alternative is
 * two purchase orders for one replenishment decision, and nothing downstream could tell that
 * apart from a buyer genuinely ordering the same goods twice.
 *
 * <p>The existence check below is the cheap path and handles ordinary redelivery. It is not the
 * guarantee: check-then-act races. The guarantee is the primary key, which is why the order is
 * inserted rather than saved — a save would merge, and merging would overwrite the very order the
 * duplicate was meant not to create twice.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "pos.order.kafka", name = "enabled", havingValue = "true")
public class PurchaseOrderCommandListener {

    /** Requesting domain, per the repo-wide {@code processed_events} convention. */
    static final String OWNER = "inventory";

    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final ProcessedEventRepository processedEventRepository;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final PurchaseOrderServiceImpl purchaseOrderService;

    @KafkaListener(
            topics = "${pos.order.kafka.order-commands-topic:order.commands.v1}",
            groupId = "${pos.order.kafka.order-commands-consumer-group:pos-order-order-commands}")
    @Transactional
    public void onOrderCommand(@NonNull String message) {
        JsonNode envelope;
        try {
            envelope = objectMapper.readTree(message);
        } catch (Exception e) {
            log.warn("Skipping unparsable order command", e);
            return;
        }
        String eventType = envelope.path("eventType").stringValue(null);
        String eventId = envelope.path("eventId").stringValue(null);
        if (eventId == null || eventId.isBlank()) {
            log.warn("Skipping order command without eventId");
            return;
        }
        if (processedEventRepository.existsById(eventId)) {
            return;
        }

        try {
            if (PurchaseOrderRequestedV1.EVENT_TYPE.equals(eventType)) {
                place(envelope);
            } else {
                log.debug("Ignoring order command type={} eventId={}", eventType, eventId);
            }
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            // The order id already exists: another delivery of this request won the insert between
            // the existence check above and the insert itself. That is the guarantee working, not a
            // failure — but the transaction is already doomed, so it is rethrown and the retry
            // finds the row and returns early. Swallowing it here would leave a rolled-back
            // transaction trying to record the event as processed.
            log.info("Purchase order already placed by a concurrent delivery; retry will no-op", e);
            throw e;
        } catch (TransientDataAccessException e) {
            // Rethrown so the container retries. Recording this as processed would drop a
            // replenishment decision on the floor: the suggestions are already marked converted,
            // so nothing would ever ask for the order again.
            throw e;
        } catch (Exception e) {
            log.warn("Skipping malformed order command eventId={}", eventId, e);
        }

        processedEventRepository.save(ProcessedEvent.builder()
                .eventId(eventId)
                .owner(OWNER)
                .processedAt(Instant.now(clock))
                .build());
    }

    private void place(JsonNode envelope) {
        PurchaseOrderRequestedV1 command =
                objectMapper.treeToValue(envelope.path("payload"), PurchaseOrderRequestedV1.class);

        if (purchaseOrderRepository.existsById(command.purchaseOrderId())) {
            log.debug("Purchase order {} already placed; ignoring repeat request", command.purchaseOrderId());
            return;
        }

        CreatePurchaseOrderRequest request = new CreatePurchaseOrderRequest();
        request.setVendorId(command.vendorId());
        request.setCurrency(command.currency());
        request.setShipToLocationId(command.shipToLocationId());
        request.setPoDate(command.poDate());
        request.setExpectedDeliveryDate(command.expectedDeliveryDate());
        request.setRequestedBy(command.requestedBy());
        request.setComment(command.comment());
        request.setLines(command.lines().stream()
                .map(PurchaseOrderCommandListener::toLineRequest)
                .toList());

        purchaseOrderService.createRequested(
                command.purchaseOrderId(),
                request,
                command.requestedBy() == null ? "pos-inventory" : command.requestedBy());
    }

    private static PurchaseOrderLineRequest toLineRequest(PurchaseOrderRequestedLine line) {
        PurchaseOrderLineRequest request = new PurchaseOrderLineRequest();
        request.setLineNumber(line.lineNumber());
        request.setSkuId(line.skuId());
        request.setDescription(line.description());
        request.setQuantity(line.quantity());
        request.setUnitCostMinor(line.unitCostMinor());
        // Carried through rather than converted here: the conversion is the order's, and doing it
        // in the service keeps one implementation of it for requested and hand-keyed orders alike.
        request.setDocumentUom(line.documentUom());
        request.setDocumentQuantity(line.documentQuantity());
        request.setTaxCodeId(line.taxCodeId());
        request.setGlAccountId(line.glAccountId());
        return request;
    }
}
