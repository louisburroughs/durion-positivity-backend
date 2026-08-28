package com.positivity.bulkloader.internal.domain;

import lombok.Data;

/**
 * One putaway rule, keyed entirely by business names.
 *
 * <p>{@code matchName} is a catalog category or subcategory name and {@code matchType} says which;
 * the destination is a site code plus a storage location name. Nothing here is a uuid, so the same
 * file loads into any environment.
 */
@Data
public class PutawayRuleLoaderRecord {

    private String priority;
    private String matchType;
    private String matchName;
    private String locationCode;
    private String destinationName;
    private String destinationStrategy;
    private String isEnabled;

    /** Resolved from {@code matchName}; stays null for the ANY tier, which must not carry one. */
    private String matchValue;

    /** Resolved from {@code locationCode} + {@code destinationName}. */
    private String destinationLocationId;
}
