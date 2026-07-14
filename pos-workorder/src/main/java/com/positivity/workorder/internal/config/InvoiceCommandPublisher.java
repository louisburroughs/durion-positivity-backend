package com.positivity.workorder.internal.config;

import com.positivity.shared.dto.InvoiceCreationRequest;
import com.positivity.shared.id.UUIDv7Generator;
import java.nio.charset.StandardCharsets;
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
 * Publishes {@code invoice.generation-requested} commands to {@code invoice.commands.v1}
 * (ADR-0044 §4, issue #900 Phase 5.4) — the async replacement for the retired synchronous
 * {@code InvoiceClient.createInvoice} call.
 *
 * <p>The send waits for the broker ack so callers get the same accepted/failed signal the old
 * REST call gave them; pos-invoice creates the invoice asynchronously and the result flows back
 * through {@code invoice.events.v1} into the {@code ext_invoice} replica, which links
 * {@code workorder.invoiceId} and resolves the pending state.
 *
 * <p>When the caller supplies an {@code Idempotency-Key} the command id is derived
 * deterministically from it, so client retries collapse to one command and pos-invoice's
 * {@code processed_events} dedupe yields exactly one invoice per key.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "workorder.kafka", name = "enabled", havingValue = "true")
public class InvoiceCommandPublisher {

    public static final String GENERATION_COMMAND_TYPE = "invoice.generation-requested";

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @Value("${workorder.kafka.invoice-commands-topic:invoice.commands.v1}")
    private String invoiceCommandsTopic;

    @Value("${workorder.kafka.command-send-timeout-ms:10000}")
    private long sendTimeoutMs;

    /**
     * Queue invoice generation for a completed workorder. Throws if the broker does not
     * acknowledge — the caller translates that into a 503, mirroring the old sync failure mode.
     */
    public void requestInvoiceGeneration(@NonNull InvoiceCreationRequest request, @Nullable String idempotencyKey) {
        UUID commandId = idempotencyKey != null && !idempotencyKey.isBlank()
                ? UUID.nameUUIDFromBytes(
                        (GENERATION_COMMAND_TYPE + ":" + idempotencyKey).getBytes(StandardCharsets.UTF_8))
                : UUIDv7Generator.generate();
        try {
            String command = objectMapper.writeValueAsString(
                    new GenerationCommand(commandId.toString(), GENERATION_COMMAND_TYPE, request));
            kafkaTemplate
                    .send(invoiceCommandsTopic, request.getWorkorderId().toString(), command)
                    .get(sendTimeoutMs, TimeUnit.MILLISECONDS);
            log.info("Queued invoice generation command {} for workorder {}", commandId, request.getWorkorderId());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while publishing invoice generation command", e);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Unable to publish invoice generation command for workorder " + request.getWorkorderId(), e);
        }
    }

    /** Command envelope for pos-invoice's {@code invoice.commands.v1} listener. */
    record GenerationCommand(
            @NonNull String commandId,
            @NonNull String commandType,
            @NonNull InvoiceCreationRequest payload) {}
}
