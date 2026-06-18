package com.positivity.workorder.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.workorder.internal.entity.EstimateSnapshot;
import com.positivity.workorder.internal.enums.EstimateStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Response DTO for estimate snapshots.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Response payload for captured estimate snapshots")
public class EstimateSnapshotResponse {

    @Schema(description = "Snapshot identifier", example = "550e8400-e29b-41d4-a716-446655440030", requiredMode = REQUIRED)
    @NotNull
    private UUID id;

    @Schema(description = "Estimate identifier", example = "550e8400-e29b-41d4-a716-446655440000", requiredMode = REQUIRED)
    @NotNull
    private UUID estimateId;

    @Schema(description = "Estimate status at capture time", example = "PENDING_APPROVAL", requiredMode = REQUIRED)
    @NotNull
    private EstimateStatus status;

    @Schema(description = "Serialized snapshot content", example = "{\"total\":162.38}", requiredMode = NOT_REQUIRED)
    private String snapshotData;

    @Schema(description = "Snapshot capture timestamp", example = "2026-01-15T09:30:00", requiredMode = NOT_REQUIRED)
    private LocalDateTime capturedAt;

    @Schema(
            description = "Actor identifier that captured the snapshot",
            example = "advisor@shop.local",
            requiredMode = NOT_REQUIRED)
    private String capturedById;

    @Nullable
    @Schema(description = "Optional snapshot notes", example = "Captured at submission", requiredMode = NOT_REQUIRED)
    private String notes;

    /**
     * Convert entity to response DTO.
     */
    @NonNull
    public static EstimateSnapshotResponse fromEntity(@NonNull EstimateSnapshot entity) {
        return EstimateSnapshotResponse.builder()
                .id(entity.getId())
                .estimateId(entity.getEstimateId())
                .status(entity.getStatus())
                .snapshotData(entity.getSnapshotData())
                .capturedAt(entity.getCapturedAt())
                .capturedById(entity.getCapturedById())
                .notes(entity.getNotes())
                .build();
    }
}
