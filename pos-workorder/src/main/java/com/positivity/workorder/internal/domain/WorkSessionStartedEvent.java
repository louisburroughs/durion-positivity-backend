package com.positivity.workorder.internal.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Domain event published when a work session is started.
 */
public record WorkSessionStartedEvent(UUID workSessionId, UUID mechanicId, Instant startAt) {}
