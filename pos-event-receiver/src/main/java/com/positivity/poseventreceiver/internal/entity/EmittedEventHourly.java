package com.positivity.poseventreceiver.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

/**
 * Read-only projection over the TimescaleDB continuous aggregate view
 * emitted_event_hourly.
 */
@Entity
@Immutable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "emitted_event_hourly")
@IdClass(EmittedEventHourlyId.class)
public class EmittedEventHourly {

    @Id
    @Column(name = "bucket")
    private Instant bucket;

    @Id
    @Column(name = "event_type")
    private String eventType;

    @Column(name = "event_count")
    private long eventCount;

    @Column(name = "avg_elapsed_ms")
    private BigDecimal avgElapsedMs;

    @Column(name = "p95_elapsed_ms")
    private double p95ElapsedMs;

    @Column(name = "p99_elapsed_ms")
    private double p99ElapsedMs;

    /**
     * Explicit dependency hook for the ArchUnit UUIDv7 rule: this is a read-only projection
     * over a TimescaleDB continuous aggregate, keyed by (bucket, eventType), not a UUID-keyed
     * aggregate. Nothing is ever inserted through this entity, so there is no identifier to
     * generate.
     */
    @Transient
    public Class<?> uuidv7Dependency() {
        return UUIDv7Id.class;
    }
}
