package com.positivity.poseventreceiver.internal.dto;

import java.util.UUID;

/**
 * DTO returned by EventType controller endpoints.
 */
public record EventTypeResponse(
        UUID id,
        String typeCode,
        String description,
        boolean active,
        String apiVersion,
        long p50Micros,
        long p95Micros,
        long p99Micros) {
}
