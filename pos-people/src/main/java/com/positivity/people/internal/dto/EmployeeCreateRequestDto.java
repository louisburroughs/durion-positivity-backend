package com.positivity.people.internal.dto;

import com.positivity.people.internal.enums.EmployeeStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;

@Data
public class EmployeeCreateRequestDto {
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
}
