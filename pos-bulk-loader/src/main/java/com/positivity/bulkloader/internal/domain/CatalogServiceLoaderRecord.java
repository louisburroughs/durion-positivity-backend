package com.positivity.bulkloader.internal.domain;

import lombok.Data;

/** A service operation as its fixture file carries it (docs/DATA_SEED_STRATEGY.md §3 Tier 2). */
@Data
public class CatalogServiceLoaderRecord {

    private String operationCode;
    private String name;
    private String shortDescription;
    private String longDescription;
    private String operationCategory;
    private String defaultLaborHours;
}
