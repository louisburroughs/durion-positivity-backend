package com.positivity.warranty.internal.client;

import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * Read-only client for pos-vehicle-inventory (vehicle registry API).
 *
 * <p>Read path: degrades to {@link Optional#empty()} on 404 or transport failure —
 * never throws across the service boundary.
 */
public interface VehicleInventoryClient {

    /**
     * Fetch a vehicle snapshot (VIN + odometer) by registry vehicle id.
     */
    @NonNull
    Optional<VehicleSnapshot> getVehicle(@NonNull UUID vehicleId);

    /**
     * Snapshot of the vehicle fields warranty claims need.
     *
     * @param odometerValue nullable — odometer may be unrecorded
     * @param odometerUnit nullable — {@code MILES} or {@code KILOMETERS}
     */
    record VehicleSnapshot(String vin, Integer odometerValue, String odometerUnit) {}
}
