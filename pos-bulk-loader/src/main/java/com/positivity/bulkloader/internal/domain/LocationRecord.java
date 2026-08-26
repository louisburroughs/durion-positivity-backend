package com.positivity.bulkloader.internal.domain;

import lombok.Data;

@Data
public class LocationRecord {
    private String name;
    private String code;
    private String addressLine1;
    private String addressLine2;
    private String city;
    private String stateOrProvince;
    private String postalCode;
    private String countryCode;
    private String phoneNumber;
    private String active;
    private String locationTypeName;
    private String timezone;
}
