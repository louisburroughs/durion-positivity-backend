package com.positivity.workorder.internal.domain;

import java.time.Instant;
import java.util.UUID;

/** Domain event published when a time entry is rejected. */
public record TimeEntryRejectedEvent(UUID timeEntryId, UUID workOrderId, String rejectedByUserId, Instant decisionAtUtc, String rejectionReason) {}
