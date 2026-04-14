package com.positivity.workorder.internal.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Domain event published when a work session is stopped.
 */
public record WorkSessionStoppedEvent(UUID workSessionId, UUID mechanicId, Instant endAt, int totalDurationSeconds) {}
