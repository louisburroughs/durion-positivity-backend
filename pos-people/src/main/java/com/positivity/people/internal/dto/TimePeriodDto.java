package com.positivity.people.internal.dto;

import com.positivity.people.internal.enums.TimePeriodStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@Schema(description = "Timekeeping time period bounding a payroll or submission cycle")
public class TimePeriodDto {

    @Schema(
            description = "Time period identifier",
            example = "01960011-0000-7000-8000-000000000001",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private UUID timePeriodId;

    @Schema(
            description = "Tenant identifier the period belongs to",
            example = "01960011-0000-7000-8000-000000000099",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private UUID tenantId;

    @Schema(
            description = "First date of the period",
            example = "2026-02-01",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private LocalDate startDate;

    @Schema(
            description = "Last date of the period",
            example = "2026-02-15",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private LocalDate endDate;

    @Schema(
            description = "Lifecycle status of the period",
            example = "OPEN",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private TimePeriodStatus status;
}
