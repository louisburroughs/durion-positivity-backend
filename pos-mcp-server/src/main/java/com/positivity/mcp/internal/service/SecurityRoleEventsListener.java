package com.positivity.mcp.internal.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.domainevents.security.RolePersonaChangedV1;
import com.positivity.mcp.internal.domain.RolePersona;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Applies role persona facts from {@code security.events.v1} (ADR-0044, issue #1613, D4).
 *
 * <p>The tier the pull-based ones cannot cover: a persona edited on a role already in the snapshot
 * never misses, so without an event it stays stale until the next scheduled re-pull.
 *
 * <p>No processed-event table, unlike the replica listeners elsewhere in the platform. The fact
 * carries the role's current persona state rather than a delta, so applying it twice is the same as
 * applying it once — a redelivery or a retry converges on the same answer. Kafka's per-partition
 * ordering plus the envelope's {@code aggregateId} record key means events for one role also arrive
 * in order, so a later edit cannot be overwritten by an earlier one.
 *
 * <p>Malformed messages are logged and skipped rather than rethrown. A poison message must not block
 * the partition, and the consequence of dropping one is bounded: the scheduled re-pull is the
 * convergence backstop, and a stale persona affects tone and emphasis only — the ROLE layer grants
 * nothing.
 */
@Component
@ConditionalOnProperty(prefix = "pos.mcp.kafka", name = "enabled", havingValue = "true")
public class SecurityRoleEventsListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(SecurityRoleEventsListener.class);

    private final ObjectMapper objectMapper;
    private final RolePersonaRefresher personaRefresher;

    public SecurityRoleEventsListener(
            @NonNull ObjectMapper objectMapper, @NonNull RolePersonaRefresher personaRefresher) {
        this.objectMapper = objectMapper;
        this.personaRefresher = personaRefresher;
    }

    @KafkaListener(
            topics = "${pos.mcp.kafka.security-events-topic:security.events.v1}",
            groupId = "${pos.mcp.kafka.security-events-consumer-group:pos-mcp-security-events}")
    public void onSecurityEvent(@NonNull String message) {
        JsonNode envelope;
        try {
            envelope = objectMapper.readTree(message);
        } catch (Exception exception) {
            LOGGER.warn("Skipping unparsable security event", exception);
            return;
        }

        String eventType = envelope.path("eventType").asText(null);
        if (!RolePersonaChangedV1.EVENT_TYPE.equals(eventType)) {
            // security.events.v1 is the whole domain's fact topic, so most traffic on it is not ours.
            LOGGER.debug("Ignoring security event type={}", eventType);
            return;
        }

        try {
            RolePersonaChangedV1 payload =
                    objectMapper.treeToValue(envelope.path("payload"), RolePersonaChangedV1.class);
            personaRefresher.applyPersona(toPersona(payload));
            LOGGER.info(
                    "MCP role persona applied from event role={} eligible={}",
                    payload.name(),
                    payload.mcpPersonaEligible());
        } catch (Exception exception) {
            LOGGER.warn("Skipping malformed role persona event: {}", exception.getMessage(), exception);
        }
    }

    private static RolePersona toPersona(RolePersonaChangedV1 payload) {
        return new RolePersona(
                payload.name(),
                payload.description(),
                payload.personaTitle(),
                payload.personaFocus(),
                payload.personaTone(),
                payload.mcpPersonaRank(),
                payload.mcpPersonaEligible());
    }
}
