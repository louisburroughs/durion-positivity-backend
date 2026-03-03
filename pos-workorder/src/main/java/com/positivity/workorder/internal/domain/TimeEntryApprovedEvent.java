package com.positivity.workorder.internal.domain;

import java.time.Instant;
import java.util.UUID;

/** Domain event published when a time entry is approved. */
public record TimeEntryApprovedEvent(UUID timeEntryId, UUID workOrderId, String approvedByUserId,
        Instant decisionAtUtc) {
}
