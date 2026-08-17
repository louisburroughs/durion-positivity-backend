package com.positivity.domainevents.workorder;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Workexec asks a fleet program to authorize payment for a workorder, published on
 * {@code workorder.events.v1} (#1346, ADR-0044 R1).
 *
 * <h2>A command, not a fact</h2>
 *
 * Unusually for this topic, this asks for something rather than reporting something that happened.
 * It is here because the alternative is workexec calling pos-supplier synchronously, and the domains
 * are walled: pos-supplier evaluates authorization, workexec owns the decision record and the
 * execution gate. The asking has to cross that wall somehow, and an event is the sanctioned way.
 *
 * <h2>Vehicle identity travels with the request</h2>
 *
 * A fleet program identifies a vehicle by plate, VIN or its own unit number, and at least one is
 * required — CAP-323's canonical request refuses to be built without one. They are carried here
 * rather than looked up by the consumer because pos-supplier has no vehicle replica and no business
 * acquiring one: the domain that owns the workorder is the one that knows which vehicle it is about.
 *
 * <h2>Re-requesting is expected</h2>
 *
 * An advisor may ask again after revising scope, and {@code attempt} says which try this is. The
 * consumer keys on {@code (supplierRef, workorderId)} so a re-request updates one authorization
 * rather than opening a second at the vendor — two live authorizations against one contract is a
 * thing somebody has to unpick with a fleet manager.
 *
 * @param workorderId    the workorder needing payment authorization
 * @param supplierRef    the fleet program to ask, from the payer's billing rules
 * @param vendorVehicleId the vendor's own vehicle id, when a previous answer supplied one
 * @param licensePlate   as registered, where known
 * @param vin            as registered, where known
 * @param fleetNumber    the fleet's own unit number, where its operator uses one
 * @param odometerValue  reading at check-in, as recorded
 * @param attempt        which request this is for this workorder, starting at 1
 * @param lines          the work being requested
 */
public record WorkorderFleetAuthRequestedV1(
        @NonNull UUID workorderId,
        @NonNull String supplierRef,
        @Nullable String vendorVehicleId,
        @Nullable String licensePlate,
        @Nullable String vin,
        @Nullable String fleetNumber,
        @Nullable String odometerValue,
        int attempt,
        @NonNull List<RequestedLine> lines) {

    /** Event type of this payload on {@code workorder.events.v1}. */
    public static final String EVENT_TYPE = "workorder.fleetauth.requested";

    /** Payload schema version; additive changes only within v1 (ADR-0044 §3). */
    public static final int SCHEMA_VERSION = 1;

    /**
     * One operation or part the fleet is being asked to cover.
     *
     * @param reference   the part or operation reference as the shop knows it
     * @param description free text for a person reading the request at the fleet
     * @param quantity    how many; never negative
     */
    public record RequestedLine(
            @Nullable String reference, @Nullable String description, int quantity) {

        public RequestedLine {
            if (quantity < 0) {
                throw new IllegalArgumentException("quantity must not be negative");
            }
        }
    }

    public WorkorderFleetAuthRequestedV1 {
        Objects.requireNonNull(workorderId, "workorderId must not be null");
        Objects.requireNonNull(supplierRef, "supplierRef must not be null");
        Objects.requireNonNull(lines, "lines must not be null");
        lines = List.copyOf(lines);
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt must be at least 1");
        }
        if (!identifiesAVehicle(vendorVehicleId, licensePlate, vin, fleetNumber)) {
            // Refused here rather than sent, because no fleet program can authorize work on a
            // vehicle nobody named — and the vendor's rejection would arrive later as an opaque
            // refusal that looks like the fleet declining to pay.
            throw new IllegalArgumentException(
                    "a fleet authorization request must identify a vehicle by vendor id, plate, VIN or fleet number");
        }
    }

    private static boolean identifiesAVehicle(
            @Nullable String vendorVehicleId,
            @Nullable String licensePlate,
            @Nullable String vin,
            @Nullable String fleetNumber) {
        return notBlank(vendorVehicleId) || notBlank(licensePlate) || notBlank(vin) || notBlank(fleetNumber);
    }

    private static boolean notBlank(@Nullable String value) {
        return value != null && !value.isBlank();
    }
}
