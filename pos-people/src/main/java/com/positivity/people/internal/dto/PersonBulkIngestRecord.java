package com.positivity.people.internal.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class PersonBulkIngestRecord {

    @NotBlank
    private String legalName;

    private String preferredName;

    @NotBlank
    private String employeeNumber;

    @NotBlank
    private String hireDate;

    private String primaryEmail;

    private String primaryPhone;
}
