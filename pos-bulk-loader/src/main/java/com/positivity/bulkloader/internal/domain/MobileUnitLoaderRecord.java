package com.positivity.bulkloader.internal.domain;

import lombok.Data;

/** One mobile unit, with its base location named by code. */
@Data
public class MobileUnitLoaderRecord {

    private String baseLocationCode;
    private String name;
    private String status;
    private String notes;

    /** Resolved from {@code baseLocationCode}, or supplied directly. */
    private String baseLocationId;
}
