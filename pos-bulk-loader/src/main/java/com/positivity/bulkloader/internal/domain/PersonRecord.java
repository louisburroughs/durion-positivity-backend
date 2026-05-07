package com.positivity.bulkloader.internal.domain;

import lombok.Data;

@Data
public class PersonRecord {

    private String legalName;
    private String preferredName;
    private String employeeNumber;
    private String hireDate;
    private String primaryEmail;
    private String primaryPhone;
}
