package com.positivity.people.internal.dto;

import com.positivity.people.internal.enums.AssignmentTerminationPolicy;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import lombok.Data;

@Data
@Schema(description = "Request to disable an employee and govern handling of their staffing assignments")
public class DisableEmployeeRequestDto {

    @Schema(description = "Reason the employee is being disabled", example = "Voluntary resignation", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String disableReason;

    @Schema(
            description = "Policy controlling how active staffing assignments are terminated",
            example = "IMMEDIATE",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private AssignmentTerminationPolicy assignmentPolicy = AssignmentTerminationPolicy.IMMEDIATE;

    @Schema(
            description = "End date to apply to assignments when the policy is GRACE_PERIOD",
            example = "2026-03-31",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private LocalDate assignmentEndDate;
}
