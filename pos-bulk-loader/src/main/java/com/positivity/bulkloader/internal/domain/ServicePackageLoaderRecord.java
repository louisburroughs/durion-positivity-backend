package com.positivity.bulkloader.internal.domain;

import lombok.Data;

/** A service package as its fixture file carries it, keyed by package code. */
@Data
public class ServicePackageLoaderRecord {

    private String packageCode;
    private String name;
    private String description;
    private String ownerScope;

    /** Resolved from {@link #ownerLocationCode}, or supplied directly. */
    private String ownerLocationId;

    private String ownerLocationCode;

    /** Resolved from {@link #fleetCustomerName}, or supplied directly. */
    private String fleetPartyId;

    /**
     * The commercial account whose standing requirement set this is, by legal or display name.
     *
     * <p>Party ids are minted when the customer pack loads, so a file that carried one would only
     * work against the environment it was written for — and a requirement set pointing at a party
     * that does not exist never matches a real fleet's query, which looks like nothing at all
     * rather than like a fault.
     */
    private String fleetCustomerName;

    private String packageLaborHours;
    private String active;
    private String effectiveFrom;
    private String effectiveTo;
}
