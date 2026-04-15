package com.positivity.poseventreceiver.internal.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
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
}
