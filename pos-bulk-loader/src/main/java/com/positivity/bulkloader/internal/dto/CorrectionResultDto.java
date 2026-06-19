package com.positivity.bulkloader.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.bulkloader.internal.enums.CorrectionStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Result of a single correction record submission")
public class CorrectionResultDto {

    @Schema(
            description = "ID of the audit record that was corrected",
            example = "00000000-0000-0000-0000-000000000001",
            requiredMode = REQUIRED)
    @NotNull
    private UUID auditRecordId;

    @Schema(
            description = "Whether the correction was accepted or rejected",
            example = "ACCEPTED",
            requiredMode = REQUIRED)
    @NotNull
    private CorrectionStatus status;

    @Schema(
            description = "Reason for rejection if status is REJECTED",
            example = "Audit record does not belong to this job",
            nullable = true,
            requiredMode = NOT_REQUIRED)
    private String rejectionReason;
}
