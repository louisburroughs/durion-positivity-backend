package com.positivity.poseventreceiver.internal.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * EventType represents a classification or category of preregistered events.
 * Maps to PreregisteredEvent via eventTypeId.
 * 
 * Includes latency percentile thresholds (p50, p95, p99) in microseconds
 * for monitoring event execution performance against SLOs.
 */
@Entity
@Table(name = "event_type")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EventType {

    /** Default threshold: 10 seconds in microseconds */
    public static final long DEFAULT_THRESHOLD_MICROS = 10_000_000L;

    /** Default API version */
    public static final String DEFAULT_API_VERSION = "1";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String typeCode;

    @Column(nullable = false)
    private String description;

    @Column(nullable = false)
    private boolean active = true;

    /**
     * API version for this event type.
     * Used to track which API version the event type belongs to.
     */
    @Column(nullable = false)
    private String apiVersion = DEFAULT_API_VERSION;

    /**
     * 50th percentile latency threshold in microseconds.
     * Events exceeding this threshold more than 50% of the time may indicate
     * issues.
     */
    @Column(nullable = false)
    private long p50Micros = DEFAULT_THRESHOLD_MICROS;

    /**
     * 95th percentile latency threshold in microseconds.
     * Events exceeding this threshold more than 5% of the time may indicate issues.
     */
    @Column(nullable = false)
    private long p95Micros = DEFAULT_THRESHOLD_MICROS;

    /**
     * 99th percentile latency threshold in microseconds.
     * Events exceeding this threshold more than 1% of the time may indicate issues.
     */
    @Column(nullable = false)
    private long p99Micros = DEFAULT_THRESHOLD_MICROS;

    public EventType(String typeCode, String description) {
        this.typeCode = typeCode;
        this.description = description;
        this.active = true;
        this.apiVersion = DEFAULT_API_VERSION;
        this.p50Micros = DEFAULT_THRESHOLD_MICROS;
        this.p95Micros = DEFAULT_THRESHOLD_MICROS;
        this.p99Micros = DEFAULT_THRESHOLD_MICROS;
    }

    public EventType(String typeCode, String description, String apiVersion, long p50Micros, long p95Micros,
            long p99Micros) {
        this.typeCode = typeCode;
        this.description = description;
        this.active = true;
        this.apiVersion = apiVersion != null ? apiVersion : DEFAULT_API_VERSION;
        this.p50Micros = p50Micros;
        this.p95Micros = p95Micros;
        this.p99Micros = p99Micros;
    }
}
