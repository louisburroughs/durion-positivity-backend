package com.positivity.bulkloader.internal.domain;

import lombok.Data;

@Data
public class CommercialCustomerRecord {
    private String legalName;
    private String displayName;
    private String taxId;
    private String billingTermsId;
    private String contactFirstName;
    private String contactLastName;
    private String contactEmail;
    private String contactPhone;
}
