package com.positivity.people.internal.dto;

import com.positivity.people.internal.enums.ApprovalStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@Schema(description = "Aggregated approval state for a person within a time period")
public class TimePeriodApprovalDto {

    @Schema(
            description = "Person identifier",
            example = "01960011-0000-7000-8000-000000000001",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private UUID personId;

    @Schema(
            description = "Time period identifier",
            example = "01960011-0000-7000-8000-000000000010",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private UUID timePeriodId;

    @Schema(
            description = "Total number of entries in the period",
            example = "10",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private int totalCount;

    @Schema(
            description = "Number of entries still pending approval",
            example = "3",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private int pendingCount;

    @Schema(description = "Number of approved entries", example = "6", requiredMode = Schema.RequiredMode.REQUIRED)
    private int approvedCount;

    @Schema(description = "Number of rejected entries", example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
    private int rejectedCount;

    @Schema(
            description = "Overall approval status for the person in the period",
            example = "PENDING_APPROVAL",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private ApprovalStatus overallStatus;
}
