package com.positivity.workorder.internal.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AssignmentUpdatedEvent {
    private UUID eventId;
    private Instant timestamp;
    private UUID workorderId;
    private AssignmentUpdatePayload payload;
}
