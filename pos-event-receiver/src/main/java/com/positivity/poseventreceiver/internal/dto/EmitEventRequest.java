package com.positivity.poseventreceiver.internal.dto;

import java.time.Instant;

/**
 * DTO for emitting event requests via REST API.
 * This record is used for both REST API calls and internal Modulith
 * communication
 * to ensure consistency between external and internal interfaces.
 */
public record EmitEventRequest(String id, long timestamp, Instant publishedAt) {
}
