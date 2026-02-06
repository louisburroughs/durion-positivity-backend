package com.positivity.workorder.internal.dto;

import com.positivity.workorder.internal.entity.WorkorderSnapshot;
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
@Schema(description = "Work order snapshot history entry")
public class WorkorderSnapshotResponse {
    @Schema(description = "Unique identifier for this snapshot record", example = "550e8400-e29b-41d4-a716-446655440000")
    private UUID id;

    @Schema(description = "ID of the work order", example = "550e8400-e29b-41d4-a716-446655440000")
    private UUID workorderId;

    @Schema(description = "Status of the work order at the time of this snapshot", example = "WORK_IN_PROGRESS")
    private String status;

    @Schema(description = "Timestamp when the snapshot was captured")
    private Instant capturedAt;

    @Schema(description = "User ID who captured the snapshot", example = "550e8400-e29b-41d4-a716-446655440000")
    private UUID capturedBy;

    @Schema(description = "Type of snapshot (e.g., MANUAL, AUTOMATIC, SYSTEM)", example = "MANUAL")
    private String snapshotType;

    @Schema(description = "Snapshot data (typically JSON)")
    private String snapshotData;

    @Schema(description = "Reason for capturing the snapshot")
    private String reason;

    public static @NonNull WorkorderSnapshotResponse fromEntity(@NonNull WorkorderSnapshot entity) {
        return WorkorderSnapshotResponse.builder()
                .id(entity.getId())
                .workorderId(entity.getWorkorderId())
                .status(entity.getStatus() != null ? entity.getStatus().name() : null)
                .capturedAt(entity.getCapturedAt())
                .capturedBy(entity.getCapturedBy())
                .snapshotType(entity.getSnapshotType())
                .snapshotData(entity.getSnapshotData())
                .reason(entity.getReason())
                .build();
    }
}
