package com.positivity.location.internal.service;

/**
 * Query-time repair-capability projection for one location.
 *
 * <p>A location is repair-capable when it owns at least one operational bay or
 * has at least one operational mobile unit based there. Nothing here is
 * persisted: the values are recomputed from aggregate queries on every request.
 *
 * Issue: #1657
 *
 * @param hasRepairCapability    whether the location can perform repairs
 * @param activeBayCount         bays owned by the location with status ACTIVE
 * @param activeMobileUnitCount  mobile units based at the location with status ACTIVE
 */
public record LocationRepairCapability(boolean hasRepairCapability, int activeBayCount, int activeMobileUnitCount) {

    /** Projection for a location with no operational bays and no operational mobile units. */
    public static final LocationRepairCapability NONE = new LocationRepairCapability(false, 0, 0);

    /**
     * Derives the capability flag from the two counts.
     *
     * @param activeBayCount        operational bay count
     * @param activeMobileUnitCount operational mobile unit count
     * @return the projection, capable when either count is positive
     */
    public static LocationRepairCapability of(int activeBayCount, int activeMobileUnitCount) {
        return new LocationRepairCapability(
                activeBayCount > 0 || activeMobileUnitCount > 0, activeBayCount, activeMobileUnitCount);
    }
}
