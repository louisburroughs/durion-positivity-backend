package com.positivity.people.internal.dto;

import com.positivity.people.internal.enums.AllowedAction;
import com.positivity.people.internal.enums.EmployeeStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@Schema(description = "Employee profile returned by employee read and write operations")
public class EmployeeProfileDto {

    @Schema(
            description = "Employee identifier",
            example = "01960011-0000-7000-8000-000000000001",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private UUID id;

    @Schema(
            description = "First (given) name of the employee",
            example = "Jane",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String firstName;

    @Schema(
            description = "Last (family) name of the employee",
            example = "Smith",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String lastName;

    @Schema(
            description = "Preferred name of the employee",
            example = "Jane",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String preferredName;

    @Schema(description = "Unique employee number", example = "EMP-0001", requiredMode = Schema.RequiredMode.REQUIRED)
    private String employeeNumber;

    @Schema(
            description = "Employment status of the employee",
            example = "ACTIVE",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private EmployeeStatus status;

    @Schema(
            description = "Date the employee was hired",
            example = "2026-01-15",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private LocalDate hireDate;

    @Schema(
            description = "Date the employee was terminated, if applicable",
            example = "2026-12-31",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private LocalDate terminationDate;

    @Schema(description = "Contact information for the employee", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private EmployeeContactInfoDto contactInfo;

    @Schema(
            description = "The employee's job role from the tenant's job-role list, or absent when none is set",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private EmployeeJobRoleDto jobRole;

    @Schema(
            description = "Timestamp the current status became effective",
            example = "2026-01-15T09:30:00Z",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private Instant statusEffectiveAt;

    @Schema(
            description = "Timestamp the employee record was created",
            example = "2026-01-15T09:30:00Z",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private Instant createdAt;

    @Schema(
            description = "Timestamp the employee record was last updated",
            example = "2026-02-01T14:05:00Z",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private Instant updatedAt;

    @Schema(
            description = "Non-fatal warnings raised while resolving the profile",
            example = "[\"Potential duplicate employee number\"]",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private List<String> warnings;

    @Schema(
            description = "Actions the calling user may take on this employee (durion#2159), computed from the "
                    + "caller's permissions and the employee's current status by EmployeeActionPolicy -- the single "
                    + "place this transition table lives. This is a RENDERING HINT ONLY: the backend continues to "
                    + "enforce authorization and status transitions independently via @PreAuthorize and its service "
                    + "guards, so a client must never treat an entry here as proof an operation will succeed. Known "
                    + "limitation: the flags consider only permissions and status, not the location-scoped access "
                    + "enforced elsewhere in this module, so a location-scoped caller may occasionally see an action "
                    + "listed that their scope does not actually cover for this employee.",
            example = "[\"VIEW_PII\", \"UPDATE\", \"DISABLE\"]",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private List<AllowedAction> allowedActions;
}
