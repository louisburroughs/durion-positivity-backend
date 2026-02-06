package com.positivity.workorder.internal.dto;

import com.positivity.workorder.internal.entity.WorkorderStateTransition;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.NonNull;

import java.time.Instant;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Work order state transition history entry")
public class WorkorderStateTransitionResponse {
    @Schema(description = "Unique identifier for this transition record", example = "550e8400-e29b-41d4-a716-446655440000")
    private UUID id;

    @Schema(description = "ID of the work order", example = "550e8400-e29b-41d4-a716-446655440000")
    private UUID workorderId;

    @Schema(description = "The status the work order transitioned FROM", example = "DRAFT")
    private String fromStatus;

    @Schema(description = "The status the work order transitioned TO", example = "WORK_IN_PROGRESS")
    private String toStatus;

    @Schema(description = "Timestamp when the transition occurred")
    private Instant transitionedAt;

    @Schema(description = "User ID who performed the transition", example = "550e8400-e29b-41d4-a716-446655440000")
    private UUID transitionedBy;

    @Schema(description = "Reason for the transition", example = "Customer approved the estimate")
    private String reason;

    @Schema(description = "Additional metadata about the transition")
    private String metadata;

    public static @NonNull WorkorderStateTransitionResponse fromEntity(@NonNull WorkorderStateTransition entity) {
        return WorkorderStateTransitionResponse.builder()
                .id(entity.getId())
                .workorderId(entity.getWorkorderId())
                .fromStatus(entity.getFromStatus() != null ? entity.getFromStatus().name() : null)
                .toStatus(entity.getToStatus() != null ? entity.getToStatus().name() : null)
                .transitionedAt(entity.getTransitionedAt())
                .transitionedBy(entity.getTransitionedBy())
                .reason(entity.getReason())
                .metadata(entity.getMetadata())
                .build();
    }
}
