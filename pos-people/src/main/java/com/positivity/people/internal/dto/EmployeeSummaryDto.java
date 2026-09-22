package com.positivity.people.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;
import lombok.Builder;
import lombok.Data;

/**
 * Employee row returned by {@code searchEmployees}: enough to identify and pick an employee from
 * a result list, not the full profile ({@link EmployeeProfileDto}).
 *
 * <p>The fields below {@code active} (durion#2155) are the employee register's extra columns
 * (username, contact info, application roles, primary location, job role): each is null unless
 * the caller requested its category on {@code include=} ({@code EmployeeSearchInclude}), and
 * {@code contactInfo} stays null regardless of {@code include=} when the caller lacks {@code
 * people:employee_pii:view} (#1898). A request that omits {@code include=} entirely gets a row
 * with every one of these fields null -- the pre-#2155 thin shape, byte for byte.
 */
@Data
@Builder
@Schema(description = "Employee row for search results; the fields below `active` are populated only when requested "
        + "via `include=` (and, for `contactInfo`, only when the caller also holds `people:employee_pii:view`)")
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

    // ── Register enrichment (durion#2155) -- see the class javadoc for the include= gating ──

    @Schema(
            description = "Login username, from the identity replica; null unless `include=USERNAME` was requested "
                    + "or the person has no linked user account",
            example = "jane.smith",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED,
            nullable = true)
    private String username;

    @Schema(
            description = "Email and phone only; null unless `include=CONTACT_INFO` was requested AND the caller "
                    + "holds people:employee_pii:view (#1898) -- a caller lacking that permission gets a 200 with "
                    + "this field simply absent, never a 403",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED,
            nullable = true)
    private EmployeeContactInfoDto contactInfo;

    @Schema(
            description = "Active application-role assignments (DECISION-PEOPLE-026); null unless "
                    + "`include=ROLE_ASSIGNMENTS` was requested, empty when requested but the person holds none",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED,
            nullable = true)
    private List<EmployeeRoleAssignmentDto> roleAssignments;

    @Schema(
            description = "The employee's single primary staffing location (DECISION-PEOPLE-004); null unless "
                    + "`include=LOCATION` was requested, or when requested but no active assignment is flagged "
                    + "primary",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED,
            nullable = true)
    private EmployeeLocationDto primaryLocation;

    @Schema(
            description = "Count of the person's other active staffing assignments beyond primaryLocation "
                    + "(\"Charlotte Main · +1 more\"); null unless `include=LOCATION` was requested, 0 when "
                    + "requested but the person has no other active assignment",
            example = "1",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED,
            nullable = true)
    private Integer otherLocationCount;

    @Schema(
            description = "The job role named on the employee profile (durion#2157); null unless "
                    + "`include=JOB_ROLE` was requested, or when requested but no job role is set",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED,
            nullable = true)
    private EmployeeJobRoleDto jobRole;
}
