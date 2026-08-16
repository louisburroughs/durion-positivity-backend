package com.positivity.supplier.service.model;

import org.jspecify.annotations.Nullable;

/**
 * A vehicle as the vendor's fleet program knows it (CAP-323 #1229).
 *
 * <p>Deliberately not reconciled against pos-vehicle-inventory. This is what the fleet says, and a
 * quoting flow needs to show exactly that: if the fleet's record disagrees with ours about a
 * license plate, the disagreement is the useful information and silently preferring one side would
 * destroy it.
 *
 * @param vendorVehicleId the vendor's identifier, used to request authorization
 * @param licensePlate    as registered with the fleet
 * @param fleetNumber     the fleet's own unit number, where it uses one
 * @param vin             as registered with the fleet
 * @param brand           vehicle make, as stated
 * @param model           vehicle model, as stated
 * @param modelYear       as stated
 * @param odometerValue   last odometer reading the fleet holds, as stated — a string because the
 *                        vendor states it as one and the unit is not always given
 */
public record FleetVehicle(
        @Nullable String vendorVehicleId,
        @Nullable String licensePlate,
        @Nullable String fleetNumber,
        @Nullable String vin,
        @Nullable String brand,
        @Nullable String model,
        @Nullable Integer modelYear,
        @Nullable String odometerValue) {

    /** Whether this record identifies a vehicle well enough to request authorization for it. */
    public boolean isIdentifiable() {
        return notBlank(vendorVehicleId) || notBlank(licensePlate) || notBlank(vin) || notBlank(fleetNumber);
    }

    private static boolean notBlank(@Nullable String value) {
        return value != null && !value.isBlank();
    }
}
