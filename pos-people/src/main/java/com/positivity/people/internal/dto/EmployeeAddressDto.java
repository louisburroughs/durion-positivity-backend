package com.positivity.people.internal.dto;

import lombok.Data;

@Data
public class EmployeeAddressDto {

    private String line1;

    private String line2;

    private String city;

    private String region;

    private String postalCode;

    private String country;

}
