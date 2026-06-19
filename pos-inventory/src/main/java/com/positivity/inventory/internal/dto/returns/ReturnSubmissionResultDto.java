package com.positivity.inventory.internal.dto.returns;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Schema(description = "Outcome of a return submission, summarizing how many lines were processed")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReturnSubmissionResultDto {
    @Schema(
            description = "Identifier of the created return record",
            example = "01960003-0000-7000-8000-00000000000a",
            requiredMode = REQUIRED)
    @NotNull
    private UUID returnId;

    @Schema(
            description = "Identifier of the workorder the return was submitted against",
            example = "01960003-0000-7000-8000-00000000000b",
            requiredMode = REQUIRED)
    @NotNull
    private UUID workorderId;

    @Schema(description = "Number of return lines that were processed", example = "2", requiredMode = REQUIRED)
    private int processedLines;

    @Schema(description = "Status of the return submission", example = "COMPLETED", requiredMode = REQUIRED)
    @NotNull
    private String status;

    @Schema(
            description = "Timestamp when the return was processed",
            example = "2026-01-15T09:30:00Z",
            requiredMode = REQUIRED)
    @NotNull
    private Instant processedAt;
}
