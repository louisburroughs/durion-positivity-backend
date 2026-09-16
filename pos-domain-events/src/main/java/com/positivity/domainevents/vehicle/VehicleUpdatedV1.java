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
 *
 * <p>{@code gvwrClass}/{@code gvwrClassSource} are the vehicle's FHWA GVWR class 1–8 and where it
 * came from ({@code OPERATOR_SET} or {@code DECODED}), additive within schema v1 for CAP-327 (spec
 * D13) — null when undetermined, and absent altogether from a fact serialized by a producer that
 * predates the field, which consumers must not read as "cleared". pos-shop-manager's {@code
 * ext_vehicle} replica holds the class for the bay duty-class ceiling check; the duty category is
 * derived from the class by whoever needs it and is never carried.
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
        @Nullable Integer gvwrClass,
        @Nullable String gvwrClassSource,
        @Nullable Instant createdAt,
        @Nullable Instant updatedAt) {

    public static final String EVENT_TYPE = "vehicle.vehicle.updated";
    public static final int SCHEMA_VERSION = 1;
}
