package com.positivity.bulkloader.internal.domain;

import lombok.Data;

/** One service bay, with its location named by code. */
@Data
public class BayLoaderRecord {

    private String locationCode;
    private String name;
    private String bayType;
    private String maxConcurrentVehicles;
    private String status;

    /** Resolved from {@code locationCode}, or supplied directly. */
    private String locationId;
}
