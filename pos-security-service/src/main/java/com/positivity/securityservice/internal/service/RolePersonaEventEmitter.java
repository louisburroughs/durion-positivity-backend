package com.positivity.securityservice.internal.service;

import com.positivity.domainevents.DomainEventEnvelope;
import com.positivity.domainevents.security.RolePersonaChangedV1;
import com.positivity.securityservice.internal.config.OutboxEventWriter;
import com.positivity.securityservice.internal.entity.Role;
import java.time.Clock;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Publishes role persona changes as facts on {@code security.events.v1} (ADR-0044, issue #1613).
 *
 * <p>pos-mcp-server derives every role-keyed prompt artifact from this data. Without an event it
 * only learns about a change on its next scheduled pull, so a persona edit on a role it already
 * knows about — which never triggers the on-miss fetch — is stale for up to the refresh interval.
 *
 * <p>Written through the transactional outbox, so a fact exists if and only if the role change
 * committed. No-op when the Kafka feature flag is off: the consumer's scheduled pull is the fallback
 * either way, which is why emitting is never allowed to fail a role write.
 */
@Slf4j
@Component
public class RolePersonaEventEmitter {

    private static final String SOURCE_SERVICE = "pos-security-service";

    private final Clock clock;
    private final ObjectProvider<OutboxEventWriter> outboxEventWriter;
    private final String securityEventsTopic;

    public RolePersonaEventEmitter(
            Clock clock,
            ObjectProvider<OutboxEventWriter> outboxEventWriter,
            @Value("${pos.security-service.kafka.security-events-topic:security.events.v1}")
                    String securityEventsTopic) {
        this.clock = clock;
        this.outboxEventWriter = outboxEventWriter;
        this.securityEventsTopic = securityEventsTopic;
    }

    /**
     * Queues a persona-changed fact for the given role, inside the caller's transaction.
     *
     * <p>Must be called from within the role write's transaction — {@link OutboxEventWriter#publish}
     * is {@code MANDATORY} precisely so an event cannot outlive a rolled-back change.
     */
    public void rolePersonaChanged(@NonNull Role role) {
        OutboxEventWriter writer = outboxEventWriter.getIfAvailable();
        if (writer == null) {
            log.debug("Kafka disabled — not emitting persona change for role={}", role.getName());
            return;
        }

        RolePersonaChangedV1 payload = new RolePersonaChangedV1(
                role.getId(),
                role.getName(),
                role.getDescription(),
                role.getPersonaTitle(),
                role.getPersonaFocus(),
                role.getPersonaTone(),
                role.getMcpPersonaRank(),
                role.isMcpPersonaEligible());

        writer.publish(
                securityEventsTopic,
                DomainEventEnvelope.of(
                        RolePersonaChangedV1.EVENT_TYPE,
                        RolePersonaChangedV1.SCHEMA_VERSION,
                        role.getId(),
                        aggregateVersion(role),
                        SOURCE_SERVICE,
                        null,
                        currentActor(),
                        payload,
                        clock));
        log.debug("Queued persona change fact for role={}", role.getName());
    }

    /**
     * {@code roles} carries no {@code @Version} column, so the last-write timestamp in epoch millis
     * stands in for the monotonic per-aggregate sequence the envelope expects. It advances on every
     * write to a given role, which is what the field is for — gap and staleness detection by a
     * consumer — and millisecond resolution is far finer than the rate any single role is edited at.
     */
    private long aggregateVersion(Role role) {
        Instant stamp = role.getLastModifiedAt() != null ? role.getLastModifiedAt() : role.getCreatedAt();
        return stamp != null ? stamp.toEpochMilli() : Instant.now(clock).toEpochMilli();
    }

    /** Audit metadata only — never authorization (ADR-0044 §5). */
    private static String currentActor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.isAuthenticated() ? authentication.getName() : "system";
    }
}
