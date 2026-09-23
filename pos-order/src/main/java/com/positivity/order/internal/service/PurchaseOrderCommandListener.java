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
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
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
 *
 * <h2>Transaction shape (#2146)</h2>
 *
 * <p>The listener method is not {@code @Transactional}; the handler and its
 * {@code processed_events} mark commit together in a {@code REQUIRES_NEW} transaction of their
 * own, and a permanent failure — even one thrown through a transactional repository or service —
 * rolls back only that transaction and is then recorded in a second one, rather than marking a
 * listener-wide transaction rollback-only, whose commit would throw
 * {@code UnexpectedRollbackException} and send the record through the container's retry and
 * dead-letter ladder with the mark rolled back each time. Transient database errors still
 * propagate for container retry. The mark commits with the order it placed, so a redelivery skips
 * at the dedupe check; the order's primary key remains the guarantee for a second request
 * carrying the same order id.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "pos.order.kafka", name = "enabled", havingValue = "true")
public class PurchaseOrderCommandListener {

    /** Requesting domain, per the repo-wide {@code processed_events} convention. */
    static final String OWNER = "inventory";

    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final ProcessedEventRepository processedEventRepository;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final PurchaseOrderServiceImpl purchaseOrderService;

    /** The event's handler work and its processed mark, in a transaction of their own; see the class doc. */
    private final TransactionTemplate handlerTransaction;

    public PurchaseOrderCommandListener(
            Clock clock,
            ObjectMapper objectMapper,
            ProcessedEventRepository processedEventRepository,
            PurchaseOrderRepository purchaseOrderRepository,
            PurchaseOrderServiceImpl purchaseOrderService,
            PlatformTransactionManager transactionManager) {
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.processedEventRepository = processedEventRepository;
        this.purchaseOrderRepository = purchaseOrderRepository;
        this.purchaseOrderService = purchaseOrderService;
        this.handlerTransaction = new TransactionTemplate(transactionManager);
        this.handlerTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @KafkaListener(
            topics = "${pos.order.kafka.order-commands-topic:order.commands.v1}",
            groupId = "${pos.order.kafka.order-commands-consumer-group:pos-order-order-commands}")
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
            handlerTransaction.executeWithoutResult(_ -> {
                if (PurchaseOrderRequestedV1.EVENT_TYPE.equals(eventType)) {
                    place(envelope);
                } else {
                    log.debug("Ignoring order command type={} eventId={}", eventType, eventId);
                }
                markProcessed(eventId);
            });
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            // The order id already exists: another delivery of this request won the insert between
            // the existence check above and the insert itself. That is the guarantee working, not a
            // failure — but the handler's transaction is already doomed, so it is rethrown and the
            // retry finds the order (or the processed mark committed with it) and returns early.
            log.info("Purchase order already placed by a concurrent delivery; retry will no-op", e);
            throw e;
        } catch (TransientDataAccessException e) {
            // Rethrown so the container retries. Recording this as processed would drop a
            // replenishment decision on the floor: the suggestions are already marked converted,
            // so nothing would ever ask for the order again.
            throw e;
        } catch (Exception e) {
            log.warn("Skipping malformed order command eventId={}", eventId, e);
            handlerTransaction.executeWithoutResult(_ -> markProcessed(eventId));
        }
    }

    private void markProcessed(String eventId) {
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
