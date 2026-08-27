package com.positivity.accounting.internal.config;

import com.positivity.shared.id.UUIDv7Generator;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Publishes command events to {@code workorder.commands.v1} (ADR-0044 R4, #842).
 *
 * <p>The send waits for the broker ack so callers get the same accepted/failed signal the old
 * synchronous REST call gave them; the work itself completes asynchronously in pos-workorder,
 * and the resulting invoice flows back through {@code invoice.events.v1} into the
 * {@code ext_invoice} replica.
 *
 * <p>Each call mints its own {@code commandId} (#1537 D1) and returns it so the caller can
 * persist it on an {@code invoice_regeneration_request} tracking row before the workorder fact
 * resolving that request ever arrives.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "pos.accounting.kafka", name = "enabled", havingValue = "true")
public class WorkorderCommandPublisher {

    public static final String INVOICE_REGENERATE_COMMAND_TYPE = "workorder.invoice.regenerate-requested";

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @Value("${pos.accounting.kafka.workorder-commands-topic:workorder.commands.v1}")
    private String workorderCommandsTopic;

    @Value("${pos.accounting.kafka.command-send-timeout-ms:10000}")
    private long sendTimeoutMs;

    /**
     * Request invoice regeneration for a workorder, returning the {@code commandId} minted for
     * this request (#1537 D1) so the caller can track it. Throws if the broker does not
     * acknowledge — the caller translates that into a 503, mirroring the old sync-call failure
     * mode. Redelivery is harmless: pos-workorder's generate-invoice is idempotent per workorder.
     */
    public @NonNull UUID requestInvoiceRegeneration(
            @NonNull UUID workorderId, @Nullable String idempotencyKey, @Nullable String requestedBy) {
        UUID commandId = UUIDv7Generator.generate();
        try {
            String command = objectMapper.writeValueAsString(new RegenerateCommand(
                    INVOICE_REGENERATE_COMMAND_TYPE,
                    commandId.toString(),
                    new RegenerateCommand.Payload(workorderId.toString(), idempotencyKey, requestedBy)));
            kafkaTemplate
                    .send(workorderCommandsTopic, workorderId.toString(), command)
                    .get(sendTimeoutMs, TimeUnit.MILLISECONDS);
            log.info("Queued invoice regeneration command {} for workorder {}", commandId, workorderId);
            return commandId;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while publishing invoice regeneration command", e);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Unable to publish invoice regeneration command for workorder " + workorderId, e);
        }
    }

    /** Command envelope for pos-workorder's {@code workorder.commands.v1} listener. */
    record RegenerateCommand(
            @NonNull String commandType,
            @NonNull String commandId,
            @NonNull Payload payload) {
        record Payload(
                @NonNull String workorderId,
                @Nullable String idempotencyKey,
                @Nullable String requestedBy) {}
    }
}
