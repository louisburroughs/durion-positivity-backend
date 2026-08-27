package com.positivity.people.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@Schema(description = "Outcome of one pay-period rollover pass")
public class TimePeriodRolloverResult {

    @Schema(description = "Periods created to cover timekeeping activity", example = "1")
    private int periodsCreated;

    @Schema(description = "OPEN periods moved to SUBMISSION_CLOSED", example = "1")
    private int submissionsClosed;

    @Schema(description = "SUBMISSION_CLOSED periods moved to PAYROLL_CLOSED", example = "1")
    private int payrollsClosed;
}
