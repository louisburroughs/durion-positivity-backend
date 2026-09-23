package com.positivity.invoice.internal.config;

import com.positivity.invoice.internal.entity.ProcessedEvent;
import com.positivity.invoice.internal.repository.ProcessedEventRepository;
import com.positivity.shared.dto.InvoiceCreationRequest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
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
 * Kafka command listener for {@code invoice.commands.v1} (ADR-0044 §4, issue #900).
 *
 * <p>Supported command types:
 * <ul>
 * <li>{@code invoice.outbox.replay-requested} — consumer-initiated drift repair and replica
 * bootstrap: re-queues published outbox events created in the requested window for
 * re-publication; consumers dedupe by eventId so replay is idempotent.</li>
 * <li>{@code invoice.generation-requested} — async invoice creation from a completed workorder
 * (#900, replaces pos-workorder's synchronous {@code InvoiceClient}). The command id is
 * deduplicated via {@code processed_events} (owner {@code invoice-commands}), so client retries
 * with the same Idempotency-Key produce exactly one invoice; the created invoice's fact on
 * {@code invoice.events.v1} links the workorder.</li>
 * </ul>
 *
 * <h2>Transaction shape</h2>
 *
 * <p>The listener method is deliberately not {@code @Transactional} (#2146): {@code createInvoice}
 * and the {@code processed_events} mark commit together in their own {@code REQUIRES_NEW}
 * transaction. Joined to one listener transaction, a permanent failure caught below had already
 * marked it rollback-only, so the commit's {@code UnexpectedRollbackException} sent the record
 * through the container's retry ladder to the DLQ; isolated, the failure rolls back only its own
 * work and is logged and dropped, left unrecorded as before. Transient failures still propagate
 * for container retry.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "pos.invoice.kafka", name = "enabled", havingValue = "true")
public class InvoiceCommandListener {

    /** Canonical dotted name normalized to command-type form: INVOICE_OUTBOX_REPLAY_REQUESTED. */
    private static final String COMMAND_OUTBOX_REPLAY_REQUESTED = "INVOICE_OUTBOX_REPLAY_REQUESTED";

    /** Canonical dotted name normalized to command-type form: INVOICE_GENERATION_REQUESTED. */
    private static final String COMMAND_GENERATION_REQUESTED = "INVOICE_GENERATION_REQUESTED";

    static final String COMMANDS_OWNER = "invoice-commands";

    /** Covers the sub-millisecond skew between outbox createdAt and the eventId timestamp. */
    private static final Duration REPLAY_WINDOW_SLACK = Duration.ofSeconds(1);

    /** Replay commands older than this are rejected — bounds repair cost. */
    @Value("${pos.invoice.outbox.replay.max-lookback:P30D}")
    private Duration replayMaxLookback;

    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final OutboxReplayService outboxReplayService;
    private final InvoiceService invoiceService;
    private final ProcessedEventRepository processedEventRepository;

    /** The invoice creation and its processed mark commit in one transaction; see the class doc. */
    private final TransactionTemplate handlerTransaction;

    public InvoiceCommandListener(
            Clock clock,
            ObjectMapper objectMapper,
            OutboxReplayService outboxReplayService,
            InvoiceService invoiceService,
            ProcessedEventRepository processedEventRepository,
            PlatformTransactionManager transactionManager) {
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.outboxReplayService = outboxReplayService;
        this.invoiceService = invoiceService;
        this.processedEventRepository = processedEventRepository;
        this.handlerTransaction = new TransactionTemplate(transactionManager);
        this.handlerTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @KafkaListener(
            topics = "${pos.invoice.kafka.commands-topic:invoice.commands.v1}",
            groupId = "${pos.invoice.kafka.commands-consumer-group:pos-invoice-commands}")
    public void onCommand(@NonNull String message) {
        try {
            JsonNode root = objectMapper.readTree(message);
            String rawCommandType = root.path("commandType").stringValue(null);
            if (rawCommandType == null || rawCommandType.isBlank()) {
                log.debug("Ignoring command without commandType: {}", message);
                return;
            }
            // Normalize dotted command names (invoice.outbox.replay-requested) to one form.
            String commandType =
                    rawCommandType.toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_');

            if (COMMAND_OUTBOX_REPLAY_REQUESTED.equals(commandType)) {
                handleOutboxReplayRequested(root);
                return;
            }
            if (COMMAND_GENERATION_REQUESTED.equals(commandType)) {
                handleGenerationRequested(root);
                return;
            }
            log.debug("Ignoring unsupported commandType={} message={}", commandType, message);
        } catch (TransientDataAccessException e) {
            // Let the container error handler retry with backoff and route to {topic}.dlq
            // (ADR-0044 §4) — replay is idempotent, so redelivery is harmless.
            throw e;
        } catch (Exception e) {
            // Malformed/unsupported commands are permanent failures: retrying cannot fix them,
            // so log and drop instead of poisoning the partition.
            log.error("Failed to process Kafka command message: {}", message, e);
        }
    }

    private void handleGenerationRequested(@NonNull JsonNode root) {
        String commandId = root.path("commandId").stringValue(null);
        if (commandId == null || commandId.isBlank()) {
            log.warn("Ignoring invoice generation command without commandId: {}", root);
            return;
        }
        if (processedEventRepository.existsById(commandId)) {
            log.debug("Skipping duplicate invoice generation command commandId={}", commandId);
            return;
        }
        InvoiceCreationRequest request = objectMapper.treeToValue(root.path("payload"), InvoiceCreationRequest.class);
        if (request == null || request.getWorkorderId() == null) {
            log.warn("Ignoring invoice generation command with missing workorderId: {}", root);
            return;
        }
        // A failure propagates to onCommand's catch: transient ones are rethrown for retry, permanent
        // ones logged and dropped, with nothing recorded (see the class doc).
        handlerTransaction.executeWithoutResult(_ -> {
            var response = invoiceService.createInvoice(request);
            processedEventRepository.save(ProcessedEvent.builder()
                    .eventId(commandId)
                    .owner(COMMANDS_OWNER)
                    .processedAt(Instant.now(clock))
                    .build());
            log.info(
                    "Invoice generation command processed commandId={} workorderId={} invoiceId={}",
                    commandId,
                    request.getWorkorderId(),
                    response.getInvoiceId());
        });
    }

    private void handleOutboxReplayRequested(@NonNull JsonNode root) {
        JsonNode payloadNode = root.get("payload");
        Instant since = parseInstant(payloadNode, "since");
        if (since == null) {
            log.warn("Ignoring outbox replay command with missing/malformed payload.since: {}", root);
            return;
        }
        Instant lookbackLimit = Instant.now(clock).minus(replayMaxLookback);
        if (since.isBefore(lookbackLimit)) {
            // A malformed or ancient `since` must not trigger a huge re-emit.
            log.warn(
                    "Ignoring outbox replay command: since={} exceeds max lookback {} (limit {})",
                    since,
                    replayMaxLookback,
                    lookbackLimit);
            return;
        }
        Instant until = parseInstant(payloadNode, "until");
        int queued;
        if (until != null && until.isAfter(since)) {
            // Bounded window repair; +/- slack covers createdAt vs eventId-timestamp skew.
            queued = outboxReplayService.replayBetween(
                    since.minus(REPLAY_WINDOW_SLACK), until.plus(REPLAY_WINDOW_SLACK));
        } else {
            queued = outboxReplayService.replaySince(since.minus(REPLAY_WINDOW_SLACK));
        }
        log.info("Outbox replay command processed since={} until={} eventsQueued={}", since, until, queued);
    }

    private @Nullable Instant parseInstant(@Nullable JsonNode payloadNode, @NonNull String field) {
        String value = payloadNode == null ? null : payloadNode.path(field).stringValue(null);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (Exception _) {
            log.warn("Malformed payload.{}={} on outbox replay command", field, value);
            return null;
        }
    }
}
