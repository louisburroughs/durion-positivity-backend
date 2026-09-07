package com.positivity.bulkloader.internal.domain;

import lombok.Data;

/** One labor-matrix adjustment step as its fixture file carries it. */
@Data
public class LaborRateAdjustmentLoaderRecord {

    /** Resolved from {@link #locationCode}, or supplied directly; blank is the platform default. */
    private String locationId;

    private String locationCode;
    private String operationCategory;
    private String adjustmentCode;
    private String description;
    private String adjustmentType;
    private String adjustmentValue;
    private String sequence;
    private String effectiveFrom;
    private String effectiveTo;
}
