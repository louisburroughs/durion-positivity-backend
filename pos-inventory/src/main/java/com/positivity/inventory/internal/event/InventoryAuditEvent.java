package com.positivity.inventory.internal.event;

import java.time.Instant;

import org.jspecify.annotations.NonNull;

/**
 * Immutable audit envelope for all inventory domain events published to Kafka.
 *
 * <p>
 * All fields are required. The event is published as a Spring application event
 * (via {@link org.springframework.context.ApplicationEventPublisher}) so that
 * contract
 * tests can capture it with {@code @RecordApplicationEvents}, and forwarded to
 * the
 * appropriate Kafka topic by an infrastructure listener.
 *
 * <p>
 * Topic and event type constants live in {@link InventoryAuditTopics}.
 *
 * @param schemaVersion monotonically increasing schema version; currently
 *                      {@code 1}
 * @param eventId       stable UUIDv7 that uniquely identifies this business
 *                      operation;
 *                      re-deliveries carry the same id (idempotency key)
 * @param eventType     human-readable event type name, e.g.
 *                      {@code "MovementAdjusted"}
 * @param topic         Kafka topic, e.g. {@code "inventory.v1.movements"}
 * @param occurredAt    business moment the operation completed
 * @param emittedAt     wall-clock instant the event was constructed
 * @param sourceSystem  always {@code "inventory"} for events produced by this
 *                      module
 * @param actor         who performed the operation (from security context, per
 *                      ADR-0018)
 * @param correlationId request correlation ID propagated from
 *                      {@code X-Correlation-Id} header
 * @param aggregate     aggregate root identity (type + id pair)
 * @param payload       event-specific business data; structure varies by
 *                      {@code eventType}
 */
public record InventoryAuditEvent(
        int schemaVersion,
        @NonNull String eventId,
        @NonNull String eventType,
        @NonNull String topic,
        @NonNull Instant occurredAt,
        @NonNull Instant emittedAt,
        @NonNull String sourceSystem,
        @NonNull AuditActorRef actor,
        @NonNull String correlationId,
        @NonNull AuditAggregateRef aggregate,
        @NonNull Object payload) {
}
