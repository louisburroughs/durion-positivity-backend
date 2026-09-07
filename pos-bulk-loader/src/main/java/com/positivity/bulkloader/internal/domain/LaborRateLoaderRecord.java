package com.positivity.bulkloader.internal.domain;

import lombok.Data;

/** An hourly labor rate as its fixture file carries it. */
@Data
public class LaborRateLoaderRecord {

    /** Resolved from {@link #locationCode}, or supplied directly; blank is the platform default. */
    private String locationId;

    /**
     * The site whose rate this is, by location code.
     *
     * <p>Blank is not a failed lookup here — it is the platform default rate, the one that answers
     * for every location which has authored none of its own. Only a code that was given and
     * matched nothing fails the row.
     */
    private String locationCode;

    private String operationCategory;
    private String currency;
    private String hourlyRate;
    private String effectiveFrom;
    private String effectiveTo;
}
