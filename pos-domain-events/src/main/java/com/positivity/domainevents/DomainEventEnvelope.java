package com.positivity.domainevents;

import com.positivity.shared.id.UUIDv7Generator;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Standard envelope for all cross-module domain and command events (ADR-0044
 * §3).
 *
 * <p>
 * Every message on a {@code {domain}.events.v1} or {@code {domain}.commands.v1}
 * topic is one
 * serialized envelope. The payload type is a versioned DTO from this library so
 * producers and
 * consumers compile against the same contract; payload changes within a schema
 * version must be
 * additive-only.
 *
 * <p>
 * The {@code actor} field is audit metadata only. Consumers must never use it
 * to derive or
 * bypass permission checks (ADR-0044 §5); user permissions are enforced at the
 * synchronous edge
 * where the initiating request entered the system.
 *
 * @param eventId          unique UUIDv7 id of this event (idempotency key for
 *                         consumers)
 * @param eventType        dotted lowercase type, e.g.
 *                         {@code customer.party.updated}
 * @param schemaVersion    payload schema version (>= 1); breaking changes
 *                         require a new topic
 * @param aggregateId      id of the owning aggregate; also used as the Kafka
 *                         record key
 * @param aggregateVersion monotonic per-aggregate sequence for gap and
 *                         staleness detection
 * @param occurredAtUtc    when the state change was committed by the owner
 * @param sourceService    producing service name, e.g. {@code pos-customer}
 * @param tenantId         tenant the fact belongs to (ADR-0062 §3). Required on
 *                         the wire: every publish path stamps it before the
 *                         envelope is serialized — the module's outbox writer
 *                         from the bound tenant ({@link #stampedWith}), a direct
 *                         sender explicitly. Null only between {@link #of} and
 *                         that stamp, and on messages published before the
 *                         field existed, which consumers must tolerate.
 * @param correlationId    correlation id propagated from the initiating
 *                         request, if available
 * @param actor            initiating user id or service name — audit only,
 *                         never authorization
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
        @Nullable UUID tenantId,
        @Nullable String correlationId,
        @Nullable String actor,
        @NonNull T payload) {

    public DomainEventEnvelope {
        requireNonNull(eventId, "eventId");
        requireNonNull(eventType, "eventType");
        requireNonNull(aggregateId, "aggregateId");
        requireNonNull(occurredAtUtc, "occurredAtUtc");
        requireNonNull(sourceService, "sourceService");
        requireNonNull(payload, "payload");
        if (!isValidEventType(eventType)) {
            throw new IllegalArgumentException(
                    "eventType must be dotted lowercase (e.g. customer.party.updated) but was: " + eventType);
        }
        if (schemaVersion < 1) {
            throw new IllegalArgumentException("schemaVersion must be >= 1 but was: " + schemaVersion);
        }
        if (aggregateVersion < 0) {
            throw new IllegalArgumentException("aggregateVersion must be >= 0 but was: " + aggregateVersion);
        }
        if (!isValidServiceName(sourceService)) {
            throw new IllegalArgumentException("sourceService must be a pos-* service name but was: " + sourceService);
        }
    }

    private static boolean isValidEventType(@Nullable String eventType) {
        if (eventType == null || eventType.isBlank()) {
            return false;
        }
        String[] segments = eventType.split("\\.");
        if (segments.length < 2) {
            return false;
        }
        for (String segment : segments) {
            if (!isLowercaseToken(segment)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isValidServiceName(@Nullable String sourceService) {
        if (sourceService == null || !sourceService.startsWith("pos-")) {
            return false;
        }
        return isLowercaseToken(sourceService.substring(4));
    }

    private static boolean isLowercaseToken(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        char first = value.charAt(0);
        if (!Character.isLowerCase(first)) {
            return false;
        }
        for (int i = 1; i < value.length(); i++) {
            char current = value.charAt(i);
            boolean isAlphaNum = (current >= 'a' && current <= 'z') || (current >= '0' && current <= '9');
            if (!(isAlphaNum || current == '-')) {
                return false;
            }
        }
        return true;
    }

    /**
     * Create an envelope with a freshly generated UUIDv7 {@code eventId} and the
     * current time from the given clock, leaving {@code tenantId} for the outbox
     * writer to stamp from the bound tenant ({@link #stampedWith}). Producers
     * should use their injected {@link Clock} so tests stay deterministic.
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
        return of(
                eventType,
                schemaVersion,
                aggregateId,
                aggregateVersion,
                sourceService,
                null,
                correlationId,
                actor,
                payload,
                clock);
    }

    /**
     * Like {@link #of(String, int, UUID, long, String, String, String, Object, Clock)} with the
     * tenant supplied by the producer: for a sender that does not go through an outbox writer
     * (a reconciliation manifest sent straight to Kafka) or one that binds the tenant itself
     * ({@code pos-tenant} publishing under the platform tenant).
     */
    public static <T> @NonNull DomainEventEnvelope<T> of(
            @NonNull String eventType,
            int schemaVersion,
            @NonNull UUID aggregateId,
            long aggregateVersion,
            @NonNull String sourceService,
            @Nullable UUID tenantId,
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
                tenantId,
                correlationId,
                actor,
                payload);
    }

    /** A copy of this envelope carrying {@code tenantId}. */
    public @NonNull DomainEventEnvelope<T> withTenantId(@NonNull UUID tenantId) {
        requireNonNull(tenantId, "tenantId");
        return new DomainEventEnvelope<>(
                eventId,
                eventType,
                schemaVersion,
                aggregateId,
                aggregateVersion,
                occurredAtUtc,
                sourceService,
                tenantId,
                correlationId,
                actor,
                payload);
    }

    /**
     * The envelope as the outbox writer publishes it under {@code boundTenant}, the tenant it
     * also stamps on the outbox row and the Kafka header: this envelope when it already carries
     * that tenant, a copy carrying it when it carries none.
     *
     * <p>An envelope that names a different tenant is refused rather than overwritten: the
     * producer built a fact for one tenant inside another tenant's binding, and publishing it
     * under either would leak across the wall (ADR-0062 §3).
     *
     * @throws IllegalStateException when this envelope carries a different tenant
     */
    public @NonNull DomainEventEnvelope<T> stampedWith(@NonNull UUID boundTenant) {
        requireNonNull(boundTenant, "boundTenant");
        if (tenantId == null) {
            return withTenantId(boundTenant);
        }
        if (!tenantId.equals(boundTenant)) {
            throw new IllegalStateException("Envelope " + eventId + " (" + eventType + ") carries tenant " + tenantId
                    + " but is being published under tenant " + boundTenant);
        }
        return this;
    }

    /**
     * The tenant this envelope carries, for a publish path that must not send an unstamped
     * envelope.
     *
     * @throws IllegalStateException when no tenant has been stamped
     */
    public @NonNull UUID requireTenantId() {
        if (tenantId == null) {
            throw new IllegalStateException(
                    "Envelope " + eventId + " (" + eventType + ") carries no tenantId; stamp it before publishing");
        }
        return tenantId;
    }

    /**
     * The Kafka record key: aggregateId, so per-aggregate ordering is preserved
     * (ADR-0044 §3).
     */
    public @NonNull String recordKey() {
        return aggregateId.toString();
    }

    private static void requireNonNull(@Nullable Object value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
    }
}
