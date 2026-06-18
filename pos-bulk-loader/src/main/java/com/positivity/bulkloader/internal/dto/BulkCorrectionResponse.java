package com.positivity.bulkloader.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Result of a bulk correction submission")
public class BulkCorrectionResponse {

    @Schema(
            description = "The bulk load job ID",
            example = "00000000-0000-0000-0000-000000000001",
            requiredMode = REQUIRED)
    @NotNull
    private UUID jobId;

    @Schema(description = "Total number of corrections submitted", example = "5", requiredMode = REQUIRED)
    private int submittedCount;

    @Schema(description = "Number of corrections accepted for processing", example = "5", requiredMode = REQUIRED)
    private int acceptedCount;

    @Schema(description = "Number of corrections rejected", example = "0", requiredMode = REQUIRED)
    private int rejectedCount;

    @Schema(
            description = "Rejection detail messages for each rejected correction, if any",
            example = "[\"Audit record does not belong to this job\"]",
            requiredMode = NOT_REQUIRED)
    private List<String> rejections;
}
