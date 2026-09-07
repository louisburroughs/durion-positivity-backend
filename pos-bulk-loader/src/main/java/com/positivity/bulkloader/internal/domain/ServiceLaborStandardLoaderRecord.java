package com.positivity.bulkloader.internal.domain;

import lombok.Data;

/** A vehicle-keyed labor standard as its fixture file carries it, with its source provenance. */
@Data
public class ServiceLaborStandardLoaderRecord {

    private String operationCode;
    private String sourceCode;
    private String sourceRevision;
    private String vehicleYear;
    private String make;
    private String model;
    private String submodel;
    private String engineCode;
    private String laborHours;
    private String timeType;
    private String overlapGroup;
    private String includedOpCodes;
    private String ownerScope;

    /** Resolved from {@link #ownerLocationCode}, or supplied directly. */
    private String ownerLocationId;

    /**
     * The site whose own standard this is, named by its location code.
     *
     * <p>Location ids are minted when the location pack loads, so a shop-scoped row that carried
     * one would only work against the environment it was written for.
     */
    private String ownerLocationCode;

    private String publishedAt;
}
