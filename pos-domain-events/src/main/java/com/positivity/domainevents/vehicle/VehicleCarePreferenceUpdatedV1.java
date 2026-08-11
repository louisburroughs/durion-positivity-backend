package com.positivity.domainevents.vehicle;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Payload for {@code vehicle.care-preference.updated} v1 on {@code vehicle.events.v1} (#1175).
 *
 * <p>Published by pos-vehicle-inventory after every care-preference mutation (upsert, merge,
 * delete). Carries the structured service-interval fact pos-vehicle-inventory owns; pos-customer
 * projects it into an {@code ext_vehicle_care_preference} replica so CRM service-due consumers
 * (the {@code service.due} segment attribute and {@code SERVICE_DUE_REMINDER} generation) can
 * resolve a per-vehicle interval instead of the module-wide default.
 *
 * <p>{@code serviceIntervalMonths} is nullable: null means "no per-vehicle override — use the
 * CRM default". A deleted preference is emitted with {@code deleted=true} and a null interval
 * (a tombstone), never as a separate event type, mirroring {@link VehicleUpdatedV1}'s
 * no-separate-deleted-event convention.
 *
 * <p>Unlike {@code vehicle.vehicle.updated}, the envelope's {@code aggregateVersion} for this
 * fact is <em>not</em> a JPA optimistic-lock version: care preferences are hard-deleted and may
 * be re-created, which would restart a row version at 0 and wedge a version-based stale guard.
 * Producers instead stamp the mutation's epoch-millis, and consumers apply a last-writer-wins
 * {@code >} guard (the {@code people-contact} replica precedent). The envelope's
 * {@code aggregateId} is the {@code vehicleId} so care-preference facts share a Kafka partition
 * (and therefore ordering) with the vehicle's registry facts.
 *
 * @param vehicleId owning vehicle (also the envelope aggregateId and record key)
 * @param serviceIntervalMonths structured service interval in whole months (1–120); null = use
 *     CRM default
 * @param deleted true when the care-preference row was deleted (tombstone)
 * @param updatedAt when the owner last modified the preference; null on deletes
 */
public record VehicleCarePreferenceUpdatedV1(
        @NonNull UUID vehicleId,
        @Nullable Integer serviceIntervalMonths,
        boolean deleted,
        @Nullable Instant updatedAt) {

    public static final String EVENT_TYPE = "vehicle.care-preference.updated";
    public static final int SCHEMA_VERSION = 1;

    /** Interval bounds, matching the owner's API validation and DB CHECK constraint. */
    public static final int MIN_SERVICE_INTERVAL_MONTHS = 1;

    public static final int MAX_SERVICE_INTERVAL_MONTHS = 120;

    public VehicleCarePreferenceUpdatedV1 {
        if (vehicleId == null) {
            throw new IllegalArgumentException("vehicleId must not be null");
        }
        if (serviceIntervalMonths != null
                && (serviceIntervalMonths < MIN_SERVICE_INTERVAL_MONTHS
                        || serviceIntervalMonths > MAX_SERVICE_INTERVAL_MONTHS)) {
            throw new IllegalArgumentException("serviceIntervalMonths must be between "
                    + MIN_SERVICE_INTERVAL_MONTHS + " and " + MAX_SERVICE_INTERVAL_MONTHS
                    + " when present but was: " + serviceIntervalMonths);
        }
        if (deleted && serviceIntervalMonths != null) {
            throw new IllegalArgumentException("a deleted care preference must not carry an interval");
        }
    }
}
