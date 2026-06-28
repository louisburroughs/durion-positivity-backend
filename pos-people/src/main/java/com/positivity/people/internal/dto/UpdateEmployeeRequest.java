package com.positivity.people.internal.dto;

import com.positivity.people.internal.enums.DuplicatePolicy;
import com.positivity.people.internal.enums.EmployeeStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import lombok.Data;

@Data
@Schema(description = "Request to update an existing employee record")
public class UpdateEmployeeRequest {

    @NotBlank(message = "firstName is required")
    @Schema(
            description = "First (given) name of the employee",
            example = "Jane",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String firstName;

    @NotBlank(message = "lastName is required")
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

    @NotBlank(message = "employeeNumber is required")
    @Schema(description = "Unique employee number", example = "EMP-0001", requiredMode = Schema.RequiredMode.REQUIRED)
    private String employeeNumber;

    @NotNull(message = "status is required")
    @Schema(
            description = "Employment status of the employee",
            example = "ACTIVE",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private EmployeeStatus status;

    @NotNull(message = "hireDate is required")
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

    @Valid
    @Schema(description = "Contact information for the employee", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private EmployeeContactInfoDto contactInfo;

    /**
     * Defines the policy for handling duplicate employee records during creation.
     * Defaults to STRICT, which will reject the request if a potential duplicate is
     * detected. Other options may include LENIENT (allow creation with a warning) or
     * IGNORE (proceed without checks).
     */
    @Schema(
            description = "Policy controlling how potential duplicate employee records are handled",
            example = "STRICT",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private DuplicatePolicy duplicatePolicy = DuplicatePolicy.STRICT;

    public void setDuplicatePolicy(DuplicatePolicy duplicatePolicy) {
        this.duplicatePolicy = duplicatePolicy == null ? DuplicatePolicy.STRICT : duplicatePolicy;
    }
}
