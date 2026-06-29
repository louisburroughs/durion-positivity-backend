package com.positivity.people.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import lombok.Builder;
import lombok.Data;

/**
 * Slim employee identity projection used to resolve an employee number to the stable
 * person identifier and employment status. Intended for service-to-service lookups
 * (for example, manager-approval-by-employee-number elevation), not for profile display.
 */
@Data
@Builder
@Schema(description = "Employee identity resolved from an employee number")
public class EmployeeIdentityDto {

    @Schema(
            description = "Employee record identifier",
            example = "01960011-0000-7000-8000-000000000001",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private UUID employeeId;

    @Schema(
            description = "Stable person identifier the employee maps to",
            example = "01960011-0000-7000-8000-000000000002",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private UUID personId;

    @Schema(description = "Employee number", example = "EMP-0001", requiredMode = Schema.RequiredMode.REQUIRED)
    private String employeeNumber;

    @Schema(description = "Employment status", example = "ACTIVE", requiredMode = Schema.RequiredMode.REQUIRED)
    private String status;

    @Schema(
            description = "True when the employee is in an ACTIVE employment status",
            example = "true",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private boolean active;
}
