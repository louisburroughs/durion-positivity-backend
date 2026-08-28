package com.positivity.bulkloader.internal.domain;

import lombok.Data;

@Data
public class VehicleBulkRecord {

    private String accountId;
    private String vin;
    private String unitNumber;
    private String description;
    private String make;
    private String model;
    private String year;
    private String trim;
    private String licensePlate;
    private String licensePlateJurisdiction;

    /**
     * Owner business keys, resolved to {@link #accountId} at load time.
     *
     * <p>A vehicle's owner is a party in another service whose id is generated when that party is
     * created, so a file cannot carry it and stay portable between environments. These two columns
     * name the owner the way a person would — {@code INDIVIDUAL}/{@code ORGANIZATION} plus the
     * party's name — and are looked up during the load. A file that already knows the account id
     * may supply it directly and leave these blank.
     */
    private String ownerType;

    private String ownerName;
}
