package com.positivity.bulkloader.internal.domain;

import lombok.Data;

/** One package membership as its fixture file carries it, naming both sides by their codes. */
@Data
public class ServicePackageMemberLoaderRecord {

    private String packageCode;
    private String operationCode;
    private String sequence;
    private String quantity;
    private String required;
}
