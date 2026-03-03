package com.positivity.workorder.internal.dto;

import com.positivity.workorder.internal.entity.EstimateSnapshot;
import com.positivity.workorder.internal.enums.EstimateStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Response DTO for estimate snapshots.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Response payload for captured estimate snapshots")
public class EstimateSnapshotResponse {

    @Schema(description = "Snapshot identifier", example = "550e8400-e29b-41d4-a716-446655440030")
    private UUID id;

    @Schema(description = "Estimate identifier", example = "550e8400-e29b-41d4-a716-446655440000")
    private UUID estimateId;

    @Schema(description = "Estimate status at capture time", example = "SUBMITTED")
    private EstimateStatus status;

    @Schema(description = "Serialized snapshot content")
    private String snapshotData;

    @Schema(description = "Snapshot capture timestamp")
    private LocalDateTime capturedAt;

    @Schema(description = "Actor identifier that captured the snapshot", example = "advisor@shop.local")
    private String capturedById;

    @Nullable
    @Schema(description = "Optional snapshot notes")
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
