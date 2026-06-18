package com.positivity.people.internal.dto;

import com.positivity.people.internal.enums.ApprovalStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@Schema(description = "Timekeeping entry summarizing a worked session pending or resolved for approval")
public class TimekeepingEntryDto {

    @Schema(description = "Timekeeping entry identifier", example = "01960011-0000-7000-8000-000000000001", requiredMode = Schema.RequiredMode.REQUIRED)
    private UUID timekeepingEntryId;

    @Schema(description = "Employee identifier", example = "01960011-0000-7000-8000-000000000002", requiredMode = Schema.RequiredMode.REQUIRED)
    private UUID employeeId;

    @Schema(description = "Session start timestamp", example = "2026-02-16T08:00:00Z", requiredMode = Schema.RequiredMode.REQUIRED)
    private Instant sessionStartTime;

    @Schema(description = "Session end timestamp", example = "2026-02-16T17:00:00Z", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private Instant sessionEndTime;

    @Schema(description = "Approval status of the entry", example = "PENDING_APPROVAL", requiredMode = Schema.RequiredMode.REQUIRED)
    private ApprovalStatus approvalStatus;

    @Schema(description = "Associated workorder identifier", example = "01960011-0000-7000-8000-000000000030", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private UUID associatedWorkOrderId;
}
