package com.positivity.people.internal.dto;

import com.positivity.people.internal.enums.DuplicatePolicy;
import com.positivity.people.internal.enums.EmployeeStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;

@Data
public class UpdateEmployeeRequest {
    @NotBlank(message = "legalName is required")
    private String legalName;

    private String preferredName;

    @NotBlank(message = "employeeNumber is required")
    private String employeeNumber;

    @NotNull(message = "status is required")
    private EmployeeStatus status;

    @NotNull(message = "hireDate is required")
    private LocalDate hireDate;

    private LocalDate terminationDate;

    @Valid
    private EmployeeContactInfoDto contactInfo;

    /**
     * Defines the policy for handling duplicate employee records during creation.
     * Defaults to STRICT, which will reject the request if a potential duplicate is
     * detected.
     * Other options may include LENIENT (allow creation with a warning) or IGNORE
     * (proceed without checks).
     */
    private DuplicatePolicy duplicatePolicy = DuplicatePolicy.STRICT;

    public void setDuplicatePolicy(DuplicatePolicy duplicatePolicy) {
        this.duplicatePolicy = duplicatePolicy == null ? DuplicatePolicy.STRICT : duplicatePolicy;
    }
}
