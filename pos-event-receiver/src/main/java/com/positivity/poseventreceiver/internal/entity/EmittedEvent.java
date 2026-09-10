package com.positivity.poseventreceiver.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import com.positivity.tenancy.TenantGlobal;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Entity representing an emitted event stored in the database.
 * Captures event execution metrics including timing and API version.
 *
 * <p>Exempt from row-level security (ADR-0062 exception, decided 2026-09-10): TimescaleDB refuses
 * compression and continuous aggregates on a hypertable with row security, and this stream keeps
 * both. Isolation rests on the {@code tenant_id} data column instead: {@code EventDaoImpl} stamps
 * it from the bound request on every row, and every query in {@code EmittedEventRepository} names
 * it. Never query this table without the tenant predicate.
 */
@TenantGlobal(
        reason = "TimescaleDB hypertable: compression and the hourly continuous aggregate exclude row security;"
                + " tenant_id is a data column stamped on every row and named in every query")
@Entity
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "emitted_event")
public class EmittedEvent {
    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "event_id", columnDefinition = "UUID")
    private UUID eventId;

    /** The event type identifier (e.g., ORDER_ORDER_CREATE) */
    @Column
    private String id;

    /** API version that triggered this event */
    @Column(name = "api_version")
    private String apiVersion;

    /** Timestamp when the event completed */
    @Column
    private long timestamp;

    /** Elapsed time in milliseconds for the operation */
    @Column(name = "elapsed_ms")
    private long elapsedMs;

    /** When the event was published */
    @Column(name = "published_at")
    private Instant publishedAt;

    /**
     * Optional identifier of the entity this event relates to. Sparse: most events do not set
     * it, which is why {@code idx_emitted_event_entity_time} is a partial index.
     */
    @Column(name = "entity_id")
    private String entityId;

    /** Producing tenant (ADR-0062): stamped by {@code EventDaoImpl} from the bound request. */
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    public EmittedEvent(
            String id, String apiVersion, long timestamp, long elapsedMs, Instant publishedAt, String entityId) {
        this.id = id;
        this.apiVersion = apiVersion;
        this.timestamp = timestamp;
        this.elapsedMs = elapsedMs;
        this.publishedAt = publishedAt;
        this.entityId = entityId;
    }
}
