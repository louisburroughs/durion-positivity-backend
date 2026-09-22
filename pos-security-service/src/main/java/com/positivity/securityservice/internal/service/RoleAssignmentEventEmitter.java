package com.positivity.securityservice.internal.service;

import com.positivity.domainevents.DomainEventEnvelope;
import com.positivity.domainevents.security.RoleAssignmentChangedV1;
import com.positivity.securityservice.internal.config.OutboxEventWriter;
import com.positivity.securityservice.internal.entity.RoleAssignment;
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
 * Publishes role assignment changes as facts on {@code security.events.v1} (ADR-0044, issue
 * #2160).
 *
 * <p>pos-security-service owns role assignments but previously published no fact when one
 * changed, so any service needing to show who holds which role had to call back here per person.
 * This closes that gap: a consumer can instead maintain its own replica from the event stream.
 *
 * <p>Written through the transactional outbox, so a fact exists if and only if the assignment
 * change committed. No-op when the Kafka feature flag is off — there is no scheduled-pull
 * fallback for this fact today, so a consumer must tolerate a gap while Kafka is disabled, which
 * is exactly why emitting must never be allowed to fail the assignment write it rides along with.
 */
@Slf4j
@Component
public class RoleAssignmentEventEmitter {

    private static final String SOURCE_SERVICE = "pos-security-service";

    private final Clock clock;
    private final ObjectProvider<OutboxEventWriter> outboxEventWriter;
    private final String securityEventsTopic;

    public RoleAssignmentEventEmitter(
            Clock clock,
            ObjectProvider<OutboxEventWriter> outboxEventWriter,
            @Value("${pos.security-service.kafka.security-events-topic:security.events.v1}")
                    String securityEventsTopic) {
        this.clock = clock;
        this.outboxEventWriter = outboxEventWriter;
        this.securityEventsTopic = securityEventsTopic;
    }

    /**
     * Queues an assignment-changed fact for the given assignment, inside the caller's
     * transaction.
     *
     * <p>Must be called from within the assignment write's transaction — {@link
     * OutboxEventWriter#publish} is {@code MANDATORY} precisely so an event cannot outlive a
     * rolled-back change. That same open transaction is also why {@code assignment.getRole()} can
     * be read here despite {@code RoleAssignment.role} being {@code @ManyToOne(fetch = LAZY)}:
     * both {@code UserRoleGrantServiceImpl.grant} and {@code .revoke}/{@code .reconcile} (via
     * {@code revokeEffective}) call this method before their own {@code @Transactional} method
     * returns, so the persistence context is still open and the lazy proxy resolves normally.
     */
    public void roleAssignmentChanged(@NonNull RoleAssignment assignment) {
        OutboxEventWriter writer = outboxEventWriter.getIfAvailable();
        if (writer == null) {
            log.debug("Kafka disabled — not emitting assignment change for assignment={}", assignment.getId());
            return;
        }

        RoleAssignmentChangedV1 payload = new RoleAssignmentChangedV1(
                assignment.getId(),
                assignment.getUser().getId(),
                assignment.getUser().getUsername(),
                assignment.getRole().getId(),
                assignment.getRole().getName(),
                assignment.getRole().getLocationScope().name(),
                assignment.getEffectiveStartDate(),
                assignment.getEffectiveEndDate(),
                assignment.getRevokedAt(),
                assignment.getTenantId());

        try {
            writer.publish(
                    securityEventsTopic,
                    DomainEventEnvelope.of(
                            RoleAssignmentChangedV1.EVENT_TYPE,
                            RoleAssignmentChangedV1.SCHEMA_VERSION,
                            assignment.getId(),
                            aggregateVersion(assignment),
                            SOURCE_SERVICE,
                            null,
                            currentActor(),
                            payload,
                            clock));
            log.debug("Queued assignment change fact for assignment={}", assignment.getId());
        } catch (RuntimeException exception) {
            // Serialization or envelope construction failing must not take the assignment write
            // with it — OutboxEventWriter.serialize throws IllegalStateException, and without this
            // an unserializable payload would turn a valid grant/revoke into a 500. A dropped fact
            // here costs a consumer's replica freshness, not the correctness of the assignment
            // itself, which already committed (or will commit) independently of this call.
            //
            // A failure of the outbox INSERT itself may still doom the surrounding transaction at
            // commit; that is a database fault, not an eventing one, and is not something this
            // catch can or should paper over.
            log.warn(
                    "Failed to queue assignment change fact for assignment={}: {}",
                    assignment.getId(),
                    exception.getMessage());
        }
    }

    /**
     * {@code role_assignments} carries no {@code @Version} column, so the last-write timestamp in
     * epoch millis stands in for the monotonic per-aggregate sequence the envelope expects, the
     * same choice {@code RolePersonaEventEmitter} makes for {@code roles}.
     */
    private long aggregateVersion(RoleAssignment assignment) {
        Instant stamp =
                assignment.getLastModifiedAt() != null ? assignment.getLastModifiedAt() : assignment.getCreatedAt();
        return stamp != null ? stamp.toEpochMilli() : Instant.now(clock).toEpochMilli();
    }

    /** Audit metadata only — never authorization (ADR-0044 §5). */
    private static String currentActor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.isAuthenticated() ? authentication.getName() : "system";
    }
}
