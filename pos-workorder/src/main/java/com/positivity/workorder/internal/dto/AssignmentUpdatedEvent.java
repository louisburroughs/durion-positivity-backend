package com.positivity.workorder.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Event payload indicating assignment context update for a workorder")
public class AssignmentUpdatedEvent {
    @NotNull
    @Schema(description = "Event identifier", example = "550e8400-e29b-41d4-a716-446655440800", requiredMode = REQUIRED)
    private UUID eventId;

    @NotNull
    @Schema(description = "Event timestamp", example = "2026-01-15T09:30:00Z", requiredMode = REQUIRED)
    private Instant timestamp;

    @NotNull
    @Schema(
            description = "Workorder identifier",
            example = "550e8400-e29b-41d4-a716-446655440000",
            requiredMode = REQUIRED)
    private UUID workorderId;

    @NotNull
    @Schema(description = "Assignment update payload", requiredMode = REQUIRED)
    private AssignmentUpdatePayload payload;
}
