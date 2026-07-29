package com.positivity.marketing.internal.service;

import com.positivity.marketing.internal.config.OutboxEventWriter;
import com.positivity.shared.id.UUIDv7Generator;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Asks pos-customer to resolve a segment's membership (Story #1148).
 *
 * <p>Segment membership is the one thing this module cannot hold as a replica: a dynamic
 * segment's members are derived from party data and change with no event to replicate from.
 * So instead of reading it synchronously, this emits a command on {@code customer.commands.v1}
 * and the owner answers with a {@code customer.segment.resolved} fact.
 *
 * <p>The command goes through the transactional outbox like every other message, so a request
 * exists if and only if the state change that asked for it committed.
 */
@Slf4j
@Component
public class SegmentResolveRequester {

    private static final String COMMAND_TYPE = "customer.segment.resolve-requested";

    private final ObjectProvider<OutboxEventWriter> outboxEventWriter;
    private final ObjectMapper objectMapper;
    private final String commandsTopic;

    public SegmentResolveRequester(
            ObjectProvider<OutboxEventWriter> outboxEventWriter,
            ObjectMapper objectMapper,
            @Value("${pos.marketing.kafka.customer-commands-topic:customer.commands.v1}") String commandsTopic) {
        this.outboxEventWriter = outboxEventWriter;
        this.objectMapper = objectMapper;
        this.commandsTopic = commandsTopic;
    }

    /**
     * Request membership for a campaign's bound segment.
     *
     * @return the correlation id the reply will carry, or empty when Kafka is disabled and no
     *     request could be sent
     */
    public java.util.Optional<UUID> requestResolve(@NonNull UUID campaignId, @NonNull UUID segmentId) {
        OutboxEventWriter writer = outboxEventWriter.getIfAvailable();
        if (writer == null) {
            log.warn("Kafka disabled; cannot request segment resolution for campaign {}", campaignId);
            return java.util.Optional.empty();
        }
        UUID requestId = UUIDv7Generator.generate();
        // The campaign id travels in the request so the reply can be matched back to it without
        // this module keeping a correlation table.
        String message = objectMapper.writeValueAsString(java.util.Map.of(
                "commandType",
                COMMAND_TYPE,
                "eventId",
                requestId.toString(),
                "payload",
                java.util.Map.of(
                        "requestId", requestId.toString(),
                        "segmentId", segmentId.toString(),
                        "campaignId", campaignId.toString())));
        // Keyed by segment so repeated requests for one segment stay ordered.
        writer.publishRaw(commandsTopic, segmentId.toString(), message);
        log.info("Requested resolution of segment {} for campaign {} (request {})", segmentId, campaignId, requestId);
        return java.util.Optional.of(requestId);
    }
}
