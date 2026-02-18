package com.positivity.people.internal.dto;

import com.positivity.people.internal.enums.EmployeeStatus;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Data
@Builder
public class EmployeeProfileDto {
    private UUID id;
    private String legalName;
    private String preferredName;
    private String employeeNumber;
    private EmployeeStatus status;
    private LocalDate hireDate;
    private LocalDate terminationDate;
    private EmployeeContactInfoDto contactInfo;
    private Instant statusEffectiveAt;
    private List<String> warnings;
}
