package com.positivity.location.internal.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class LocationBulkIngestRecord {

    @NotBlank
    private String name;

    @NotBlank
    private String code;

    private String addressLine1;
    private String addressLine2;
    private String city;
    private String stateOrProvince;
    private String postalCode;
    private String countryCode;
    private String phoneNumber;
    private Boolean active;
    private String locationTypeName;
}
