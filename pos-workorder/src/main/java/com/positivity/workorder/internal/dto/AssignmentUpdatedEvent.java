package com.positivity.workorder.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
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
@Schema(description = "Event payload indicating assignment context update for a workorder")
public class AssignmentUpdatedEvent {
    @Schema(description = "Event identifier", example = "550e8400-e29b-41d4-a716-446655440800")
    private UUID eventId;

    @Schema(description = "Event timestamp")
    private Instant timestamp;

    @Schema(description = "Workorder identifier", example = "550e8400-e29b-41d4-a716-446655440000")
    private UUID workorderId;

    @Schema(description = "Assignment update payload")
    private AssignmentUpdatePayload payload;
}
