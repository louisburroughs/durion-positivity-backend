package com.positivity.people.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import lombok.Builder;
import lombok.Data;

/**
 * Slim employee row returned by {@code searchEmployees}: enough to identify and pick an
 * employee from a result list, not the full profile ({@link EmployeeProfileDto}).
 */
@Data
@Builder
@Schema(description = "Slim employee row for search results")
public class EmployeeSummaryDto {

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

    @Schema(description = "Employee number", example = "EMP-0001", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String employeeNumber;

    @Schema(
            description = "First (given) name, from the identity replica; null when the replica has not caught up",
            example = "Jane",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String firstName;

    @Schema(
            description = "Last (family) name, from the identity replica; null when the replica has not caught up",
            example = "Smith",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String lastName;

    @Schema(
            description = "Preferred name, from the identity replica",
            example = "Janie",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String preferredName;

    @Schema(description = "Employment status", example = "ACTIVE", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String status;

    @Schema(
            description = "True when the employee is in an ACTIVE employment status",
            example = "true",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private boolean active;
}
