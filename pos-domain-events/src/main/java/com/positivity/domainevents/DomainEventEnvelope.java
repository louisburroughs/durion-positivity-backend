package com.positivity.domainevents;

import com.positivity.shared.id.UUIDv7Generator;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Standard envelope for all cross-module domain and command events (ADR-0044 §3).
 *
 * <p>Every message on a {@code {domain}.events.v1} or {@code {domain}.commands.v1} topic is one
 * serialized envelope. The payload type is a versioned DTO from this library so producers and
 * consumers compile against the same contract; payload changes within a schema version must be
 * additive-only.
 *
 * <p>The {@code actor} field is audit metadata only. Consumers must never use it to derive or
 * bypass permission checks (ADR-0044 §5); user permissions are enforced at the synchronous edge
 * where the initiating request entered the system.
 *
 * @param eventId          unique UUIDv7 id of this event (idempotency key for consumers)
 * @param eventType        dotted lowercase type, e.g. {@code customer.party.updated}
 * @param schemaVersion    payload schema version (>= 1); breaking changes require a new topic
 * @param aggregateId      id of the owning aggregate; also used as the Kafka record key
 * @param aggregateVersion monotonic per-aggregate sequence for gap and staleness detection
 * @param occurredAtUtc    when the state change was committed by the owner
 * @param sourceService    producing service name, e.g. {@code pos-customer}
 * @param correlationId    correlation id propagated from the initiating request, if available
 * @param actor            initiating user id or service name — audit only, never authorization
 * @param payload          versioned payload DTO
 * @param <T>              payload contract type
 */
public record DomainEventEnvelope<T>(
        @NonNull UUID eventId,
        @NonNull String eventType,
        int schemaVersion,
        @NonNull UUID aggregateId,
        long aggregateVersion,
        @NonNull Instant occurredAtUtc,
        @NonNull String sourceService,
        @Nullable String correlationId,
        @Nullable String actor,
        @NonNull T payload) {

    private static final Pattern EVENT_TYPE_PATTERN = Pattern.compile("[a-z][a-z0-9-]*(\\.[a-z][a-z0-9-]*)+");
    private static final Pattern SOURCE_SERVICE_PATTERN = Pattern.compile("pos-[a-z][a-z0-9-]*");

    public DomainEventEnvelope {
        requireNonNull(eventId, "eventId");
        requireNonNull(eventType, "eventType");
        requireNonNull(aggregateId, "aggregateId");
        requireNonNull(occurredAtUtc, "occurredAtUtc");
        requireNonNull(sourceService, "sourceService");
        requireNonNull(payload, "payload");
        if (!EVENT_TYPE_PATTERN.matcher(eventType).matches()) {
            throw new IllegalArgumentException(
                    "eventType must be dotted lowercase (e.g. customer.party.updated) but was: " + eventType);
        }
        if (schemaVersion < 1) {
            throw new IllegalArgumentException("schemaVersion must be >= 1 but was: " + schemaVersion);
        }
        if (aggregateVersion < 0) {
            throw new IllegalArgumentException("aggregateVersion must be >= 0 but was: " + aggregateVersion);
        }
        if (!SOURCE_SERVICE_PATTERN.matcher(sourceService).matches()) {
            throw new IllegalArgumentException("sourceService must be a pos-* service name but was: " + sourceService);
        }
    }

    /**
     * Create an envelope with a freshly generated UUIDv7 {@code eventId} and the current time from
     * the given clock. Producers should use their injected {@link Clock} so tests stay
     * deterministic.
     */
    public static <T> @NonNull DomainEventEnvelope<T> of(
            @NonNull String eventType,
            int schemaVersion,
            @NonNull UUID aggregateId,
            long aggregateVersion,
            @NonNull String sourceService,
            @Nullable String correlationId,
            @Nullable String actor,
            @NonNull T payload,
            @NonNull Clock clock) {
        return new DomainEventEnvelope<>(
                UUIDv7Generator.generate(),
                eventType,
                schemaVersion,
                aggregateId,
                aggregateVersion,
                Instant.now(clock),
                sourceService,
                correlationId,
                actor,
                payload);
    }

    /** The Kafka record key: aggregateId, so per-aggregate ordering is preserved (ADR-0044 §3). */
    public @NonNull String recordKey() {
        return aggregateId.toString();
    }

    private static void requireNonNull(@Nullable Object value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
    }
}
