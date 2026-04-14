package com.positivity.events;

import lombok.Builder;
import lombok.Data;

/**
 * DTO for registering event types with the Event Receiver service.
 * Used by module initializers to register their event types at startup.
 */
@Data
@Builder
public class EventTypeRegistration {

    /** Unique code identifying the event type (e.g., ORDER_ORDER_CREATE) */
    private String typeCode;

    /** Human-readable description of the event type */
    private String description;

    /** API version for this event type */
    @Builder.Default
    private String apiVersion = "1";

    /** Whether the event type is active */
    @Builder.Default
    private boolean active = true;

    /** 50th percentile latency threshold in microseconds */
    private Long p50Micros;

    /** 95th percentile latency threshold in microseconds */
    private Long p95Micros;

    /** 99th percentile latency threshold in microseconds */
    private Long p99Micros;

    // ==================== THRESHOLD PRESETS ====================

    /** Fast read operations: p50=50ms, p95=200ms, p99=500ms */
    public static final long FAST_READ_P50 = 50_000L;

    public static final long FAST_READ_P95 = 200_000L;
    public static final long FAST_READ_P99 = 500_000L;

    /** Search/filter operations: p50=100ms, p95=500ms, p99=1s */
    public static final long SEARCH_P50 = 100_000L;

    public static final long SEARCH_P95 = 500_000L;
    public static final long SEARCH_P99 = 1_000_000L;

    /** Write/create operations: p50=200ms, p95=1s, p99=3s */
    public static final long WRITE_P50 = 200_000L;

    public static final long WRITE_P95 = 1_000_000L;
    public static final long WRITE_P99 = 3_000_000L;

    /** Approval workflow operations: p50=500ms, p95=2s, p99=5s */
    public static final long APPROVAL_P50 = 500_000L;

    public static final long APPROVAL_P95 = 2_000_000L;
    public static final long APPROVAL_P99 = 5_000_000L;

    // ==================== BUILDER HELPERS ====================

    /**
     * Create a registration for a fast read operation.
     */
    public static EventTypeRegistrationBuilder fastRead(String typeCode, String description) {
        return EventTypeRegistration.builder()
                .typeCode(typeCode)
                .description(description)
                .p50Micros(FAST_READ_P50)
                .p95Micros(FAST_READ_P95)
                .p99Micros(FAST_READ_P99);
    }

    /**
     * Create a registration for a search/filter operation.
     */
    public static EventTypeRegistrationBuilder search(String typeCode, String description) {
        return EventTypeRegistration.builder()
                .typeCode(typeCode)
                .description(description)
                .p50Micros(SEARCH_P50)
                .p95Micros(SEARCH_P95)
                .p99Micros(SEARCH_P99);
    }

    /**
     * Create a registration for a write/create operation.
     */
    public static EventTypeRegistrationBuilder write(String typeCode, String description) {
        return EventTypeRegistration.builder()
                .typeCode(typeCode)
                .description(description)
                .p50Micros(WRITE_P50)
                .p95Micros(WRITE_P95)
                .p99Micros(WRITE_P99);
    }

    /**
     * Create a registration for an approval workflow operation.
     */
    public static EventTypeRegistrationBuilder approval(String typeCode, String description) {
        return EventTypeRegistration.builder()
                .typeCode(typeCode)
                .description(description)
                .p50Micros(APPROVAL_P50)
                .p95Micros(APPROVAL_P95)
                .p99Micros(APPROVAL_P99);
    }
}
