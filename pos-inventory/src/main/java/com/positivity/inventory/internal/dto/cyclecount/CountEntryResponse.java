package com.positivity.inventory.internal.dto.cyclecount;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response DTO for cycle count history entries.
 */
@Schema(description = "A single recorded count entry in a cycle count task's history")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CountEntryResponse {

    @Schema(
            description = "Unique identifier of the count entry",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    @NotNull
    private UUID countEntryId;

    @Schema(
            description = "Identifier of the cycle count task this entry belongs to",
            example = "01960003-0000-7000-8000-000000000002",
            requiredMode = REQUIRED)
    @NotNull
    private UUID cycleCountTaskId;

    @Schema(
            description = "Identifier of the auditor who recorded the count",
            example = "auditor-10042",
            requiredMode = REQUIRED)
    @NotNull
    private String auditorId;

    @Schema(description = "Quantity physically counted by the auditor", example = "12", requiredMode = REQUIRED)
    @NotNull
    private Integer actualQuantity;

    @Schema(description = "Quantity expected on hand at the time of the count", example = "15", requiredMode = REQUIRED)
    @NotNull
    private Integer expectedQuantity;

    @Schema(
            description = "Difference between counted and expected quantity (actual minus expected)",
            example = "-3",
            requiredMode = REQUIRED)
    @NotNull
    private Integer variance;

    @Schema(
            description = "Sequence number of this count within the recount chain; 0 for the initial count",
            example = "1",
            requiredMode = REQUIRED)
    @NotNull
    private Integer recountSequenceNumber;

    @Schema(
            description = "Identifier of the count entry this entry is a recount of, if any",
            example = "01960003-0000-7000-8000-000000000003",
            requiredMode = NOT_REQUIRED)
    private UUID recountOfCountEntryId;

    @Schema(
            description = "Timestamp when the count was recorded",
            example = "2026-01-15T09:30:00Z",
            requiredMode = REQUIRED)
    @NotNull
    private Instant countedAt;

    @Schema(
            description = "Whether this entry is a recount of a prior count",
            example = "false",
            requiredMode = REQUIRED)
    private boolean recount;
}
