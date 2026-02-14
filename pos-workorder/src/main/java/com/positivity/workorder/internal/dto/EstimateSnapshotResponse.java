package com.positivity.workorder.internal.dto;

import com.positivity.workorder.internal.entity.EstimateSnapshot;
import com.positivity.workorder.internal.entity.EstimateStatus;
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
public class EstimateSnapshotResponse {

    private UUID id;
    private UUID estimateId;
    private EstimateStatus status;
    private String snapshotData;
    private LocalDateTime capturedAt;
    private UUID capturedById;
    @Nullable
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
