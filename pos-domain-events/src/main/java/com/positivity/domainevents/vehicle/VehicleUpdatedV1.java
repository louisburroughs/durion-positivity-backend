package com.positivity.domainevents.vehicle;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Payload for {@code vehicle.vehicle.updated} v1 on {@code vehicle.events.v1} (ADR-0044 §6,
 * #843).
 *
 * <p>Published by pos-vehicle-inventory after every vehicle-registry mutation (create, update,
 * ownership transfer, deactivation). Carries the registry facts pos-vehicle-inventory owns.
 * Deactivation is an update with {@code active=false} — there is no separate deleted event,
 * mirroring the registry's soft-delete semantics.
 *
 * <p>{@code accountId} is the owning party id (a pos-customer party UUID). pos-customer's
 * consumer uses it to maintain the vehicle-party association it owns per ADR-0012; an
 * {@code accountId} change on an existing vehicle is an ownership transfer.
 *
 * <p>The envelope's {@code aggregateVersion} is the vehicle row's JPA optimistic-lock version,
 * so consumers can drop stale out-of-order deliveries.
 *
 * <p>{@code odometerValue}/{@code odometerUnit} are the registry's last-recorded odometer reading
 * (additive within schema v1, ADR-0044 §3 — null for producers/older events that carry none).
 * {@code odometerUnit} is the {@code OdometerUnit} name ({@code MILES}/{@code KILOMETERS}). They
 * feed pos-warranty's {@code ext_vehicle} replica, which froze the VIN + odometer onto a claim
 * that the retired synchronous {@code VehicleInventoryClient} previously supplied (#924).
 */
public record VehicleUpdatedV1(
        @NonNull UUID vehicleId,
        @NonNull UUID accountId,
        @NonNull String vin,
        @NonNull String vinNormalized,
        @Nullable String unitNumber,
        @Nullable String description,
        @Nullable String licensePlate,
        @Nullable String licensePlateJurisdiction,
        @Nullable Integer year,
        @Nullable String make,
        @Nullable String model,
        @Nullable String trim,
        boolean active,
        @Nullable Integer odometerValue,
        @Nullable String odometerUnit,
        @Nullable Instant createdAt,
        @Nullable Instant updatedAt) {

    public static final String EVENT_TYPE = "vehicle.vehicle.updated";
    public static final int SCHEMA_VERSION = 1;
}
