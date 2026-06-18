package com.positivity.workorder.internal.dto.pick;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
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
@Schema(description = "Response describing a workorder pick list")
public class WorkorderPickListResponse {

    @Schema(
            description = "Pick list identifier",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    private UUID pickListId;

    @Schema(
            description = "Identifier of the workorder the pick list belongs to",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    private UUID workorderId;

    @Schema(description = "Status of the pick list", example = "OPEN", requiredMode = REQUIRED)
    private String status;

    @Schema(description = "Priority ordering of the pick list", example = "2", requiredMode = REQUIRED)
    private int priority;

    @Schema(description = "Timestamp the pick list is due", example = "2026-01-15T09:30:00Z", requiredMode = REQUIRED)
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Instant dueAt;

    @Schema(
            description = "Timestamp the pick list was created",
            example = "2026-01-15T09:30:00Z",
            requiredMode = REQUIRED)
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Instant createdAt;

    @Schema(
            description = "Timestamp the pick list was last updated",
            example = "2026-01-15T09:30:00Z",
            requiredMode = REQUIRED)
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Instant updatedAt;
}
