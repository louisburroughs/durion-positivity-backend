package com.positivity.bulkloader.internal.domain;

import lombok.Data;

@Data
public class CustomerPersonRecord {

    private String firstName;
    private String lastName;
    private String email;
    private String phoneNumber;
    private String primaryAddress;
    private String customerNumber;
}
