package com.positivity.inventory.internal.dto.cyclecount;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.inventory.internal.enums.TaskStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response after submitting a count or recount.
 */
@Schema(description = "Result of submitting a count or recount for a cycle count task")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CountResponse {

    @Schema(
            description = "Unique identifier of the recorded count entry",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    @NotNull
    private UUID countEntryId;

    @Schema(
            description = "Identifier of the cycle count task the count was submitted against",
            example = "01960003-0000-7000-8000-000000000002",
            requiredMode = REQUIRED)
    @NotNull
    private UUID taskId;

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
            description = "Resulting status of the cycle count task after the submission",
            example = "COUNTED_PENDING_REVIEW",
            requiredMode = REQUIRED)
    @NotNull
    private TaskStatus taskStatus;

    @Schema(
            description = "Timestamp when the count was recorded",
            example = "2026-01-15T09:30:00Z",
            requiredMode = REQUIRED)
    @NotNull
    private Instant countedAt;

    @Schema(
            description = "Whether the maximum allowed number of recounts has been exceeded",
            example = "false",
            requiredMode = REQUIRED)
    private boolean limitExceeded;

    @Schema(
            description = "Human-readable message describing the outcome of the submission",
            example = "Count submitted and awaiting review",
            requiredMode = NOT_REQUIRED)
    private String message;
}
