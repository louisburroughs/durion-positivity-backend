package com.positivity.bulkloader.internal.domain;

import lombok.Data;

/**
 * One storage location, with its site named by code.
 *
 * <p>The parent is carried as a name and resolved by the owning service, which is the only place
 * that can see both what already exists at the site and what earlier rows of the same batch just
 * created.
 */
@Data
public class StorageLocationLoaderRecord {

    private String locationCode;
    private String name;
    private String type;
    private String parentName;
    private String storageCategoryCode;
    private String hazardContainment;
    private String allowNewProduct;
    private String maxUnitCount;
    private String status;

    /** Resolved from {@code locationCode}, or supplied directly. */
    private String siteId;
}
