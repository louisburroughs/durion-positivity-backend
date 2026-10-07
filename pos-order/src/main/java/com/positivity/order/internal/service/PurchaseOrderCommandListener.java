package com.positivity.order.internal.service;

import com.positivity.domainevents.order.PurchaseOrderRequestedLine;
import com.positivity.domainevents.order.PurchaseOrderRequestedV1;
import com.positivity.kafka.common.KafkaRails;
import com.positivity.order.internal.dto.purchaseorder.CreatePurchaseOrderRequest;
import com.positivity.order.internal.dto.purchaseorder.PurchaseOrderLineRequest;
import com.positivity.order.internal.entity.ProcessedEvent;
import com.positivity.order.internal.repository.ProcessedEventRepository;
import com.positivity.order.internal.repository.PurchaseOrderRepository;
import com.positivity.tenancy.kafka.RetryableConsumerFailures;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The single consumer of {@code order.commands.v1}: places purchase orders that other domains ask for
 * (CAP-320 #1334, ADR-0044 R1), and serves {@code order.outbox.replay-requested} drift repairs from
 * consumers of {@code order.events.v1} (ADR-0044 §4, #2579).
 *
 * <h2>The replay command</h2>
 *
 * A manifest listener asks with the platform's command shape — {@code commandType} and a {@code payload}
 * of {@code since} / {@code until}, no event id — so it is dispatched before the event-id guard and
 * recorded nowhere: replay is idempotent (the rows are re-sent with their original event ids, which
 * consumers dedupe), as in every other owner. Only the tenant the command arrived under is replayed, and
 * only {@code order.events.v1} rows ({@link OrderOutboxReplayService}). A window with an {@code until} is
 * replayed as {@code [since, until)}, one without as everything since {@code since}, each widened by a
 * second for the skew between an outbox row's {@code createdAt} and its event id. A window starting further
 * back than {@code pos.order.outbox.replay.max-lookback} (default {@code P30D}) or without a parsable
 * {@code since} is logged and dropped; a transient database failure propagates for the container to retry.
 *
 * <p>One consumer, not one per command, for the same reason as pos-supplier's {@code
 * SupplierCommandListener}: a second consumer group on this topic would see every purchase-order command
 * and every replay request the other one handles.
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
@KafkaRails
public class PurchaseOrderCommandListener {

    /** Requesting domain, per the repo-wide {@code processed_events} convention. */
    static final String OWNER = "inventory";

    /** Wire name {@code order.outbox.replay-requested}, in normalized command-type form. */
    static final String COMMAND_OUTBOX_REPLAY_REQUESTED = "ORDER_OUTBOX_REPLAY_REQUESTED";

    /** Covers the sub-millisecond skew between an outbox row's createdAt and its event id's timestamp. */
    private static final Duration REPLAY_WINDOW_SLACK = Duration.ofSeconds(1);

    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final ProcessedEventRepository processedEventRepository;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final PurchaseOrderServiceImpl purchaseOrderService;
    private final OrderOutboxReplayService outboxReplayService;

    /** How far back a replay window may start; older requests are logged and dropped. */
    private final Duration replayMaxLookback;

    /** The event's handler work and its processed mark, in a transaction of their own; see the class doc. */
    private final TransactionTemplate handlerTransaction;

    public PurchaseOrderCommandListener(
            Clock clock,
            ObjectMapper objectMapper,
            ProcessedEventRepository processedEventRepository,
            PurchaseOrderRepository purchaseOrderRepository,
            PurchaseOrderServiceImpl purchaseOrderService,
            OrderOutboxReplayService outboxReplayService,
            @Value("${pos.order.outbox.replay.max-lookback:P30D}") Duration replayMaxLookback,
            PlatformTransactionManager transactionManager) {
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.processedEventRepository = processedEventRepository;
        this.purchaseOrderRepository = purchaseOrderRepository;
        this.purchaseOrderService = purchaseOrderService;
        this.outboxReplayService = outboxReplayService;
        this.replayMaxLookback = replayMaxLookback;
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
        String commandType = envelope.path("commandType").stringValue(null);
        if (commandType != null
                && COMMAND_OUTBOX_REPLAY_REQUESTED.equals(
                        commandType.toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_'))) {
            handleOutboxReplayRequested(envelope);
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
        } catch (Exception e) {
            if (RetryableConsumerFailures.isRetryable(e)) {
                // Rethrown so the container retries. Recording this as processed would drop a
                // replenishment decision on the floor: the suggestions are already marked converted,
                // so nothing would ever ask for the order again.
                throw e;
            }
            log.warn("Skipping malformed order command eventId={}", eventId, e);
            handlerTransaction.executeWithoutResult(_ -> markProcessed(eventId));
        }
    }

    /**
     * Re-queues the bound tenant's {@code order.events.v1} rows of the requested window (#2579). Malformed
     * and out-of-lookback requests are dropped after logging; a transient database failure propagates so
     * the container retries it (ADR-0044 §4).
     */
    private void handleOutboxReplayRequested(@NonNull JsonNode command) {
        JsonNode payload = command.path("payload");
        Instant since = parseInstant(payload, "since");
        if (since == null) {
            log.warn("Ignoring order outbox replay command with missing/malformed payload.since: {}", command);
            return;
        }
        Instant lookbackLimit = Instant.now(clock).minus(replayMaxLookback);
        if (since.isBefore(lookbackLimit)) {
            log.warn(
                    "Ignoring order outbox replay command: since={} exceeds max lookback {} (limit {})",
                    since,
                    replayMaxLookback,
                    lookbackLimit);
            return;
        }
        Instant until = parseInstant(payload, "until");
        try {
            int queued = until != null && until.isAfter(since)
                    ? outboxReplayService.replayBetween(
                            since.minus(REPLAY_WINDOW_SLACK), until.plus(REPLAY_WINDOW_SLACK))
                    : outboxReplayService.replaySince(since.minus(REPLAY_WINDOW_SLACK));
            log.info("Order outbox replay command processed since={} until={} eventsQueued={}", since, until, queued);
        } catch (RuntimeException e) {
            if (RetryableConsumerFailures.isRetryable(e)) {
                // The container retries with backoff, then publishes to {topic}.dlq (ADR-0044 §4); a replay
                // is idempotent, so redelivery is harmless.
                throw e;
            }
            log.error("Order outbox replay command failed and will not be retried: {}", command, e);
        }
    }

    private static @Nullable Instant parseInstant(@NonNull JsonNode payload, @NonNull String field) {
        String value = payload.path(field).stringValue(null);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException _) {
            log.warn("Malformed payload.{}={} on order outbox replay command", field, value);
            return null;
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
