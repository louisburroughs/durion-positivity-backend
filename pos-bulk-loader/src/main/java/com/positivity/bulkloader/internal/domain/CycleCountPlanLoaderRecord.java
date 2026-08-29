package com.positivity.bulkloader.internal.domain;

import lombok.Data;

/** One cycle count plan, with its site and zones named rather than identified. */
@Data
public class CycleCountPlanLoaderRecord {

    private String locationCode;
    private String planName;

    /** Pipe-separated storage location names, e.g. {@code Bin A-01|Bin A-02}. */
    private String zoneNames;

    private String scheduledDaysOut;
    private String scheduledDate;

    /** Resolved from {@code locationCode}. */
    private String locationId;

    /** Resolved from {@code zoneNames}, comma-separated for the ingest payload. */
    private String zoneIds;
}
