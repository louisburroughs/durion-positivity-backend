package com.positivity.inventory.internal.dto.receiving;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Schema(description = "A workorder eligible to receive a cross-docked receiving line (#2211)")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CrossDockWorkorderSearchResultDto {

    @Schema(
            description = "Identifier of the workorder",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    private UUID workorderId;

    @Schema(description = "Human-readable workorder number", example = "WO-2026-0042", requiredMode = NOT_REQUIRED)
    private String workorderNumber;

    @Schema(description = "Current workorder status", example = "IN_PROGRESS", requiredMode = NOT_REQUIRED)
    private String status;

    @Schema(description = "Number of part lines the workorder demands", example = "3", requiredMode = REQUIRED)
    private long partLineCount;

    @Schema(
            description = "Timestamp the workorder replica was last updated",
            example = "2026-01-15T09:30:00Z",
            requiredMode = NOT_REQUIRED)
    private Instant updatedAt;
}
