package com.positivity.poseventreceiver.internal.dto;

import java.time.Instant;

/**
 * DTO for emitting event requests via REST API.
 * This record is used for REST API calls communication
 * to ensure consistency between external and internal interfaces.
 */
public record EmitEventRequest(
        String id,
        String apiVersion,
        long timestamp,
        long elapsedMs,
        Instant publishedAt) {
}
