package com.positivity.inventory.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Schema(description = "Result of applying a resolution to an inventory shortage")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShortageResolutionResultDto {

    @Schema(
            description = "Identifier of the allocation that was resolved",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    @NotNull
    private UUID allocationId;

    @Schema(description = "Resolution strategy that was applied", example = "SUBSTITUTE", requiredMode = REQUIRED)
    @NotNull
    private String resolution;

    @Schema(
            description = "Timestamp when the shortage was resolved",
            example = "2026-01-15T09:30:00Z",
            requiredMode = REQUIRED)
    @NotNull
    private Instant resolvedAt;

    @Schema(
            description = "Resulting status of the allocation after resolution",
            example = "RESOLVED",
            requiredMode = REQUIRED)
    @NotNull
    private String status;
}
