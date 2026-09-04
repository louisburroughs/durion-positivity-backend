package com.positivity.supplier.internal.domain.model;

import com.positivity.supplier.internal.exception.SupplierValidationException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * What a caller must supply to ask a fleet program to authorize work (CAP-323 #1229).
 *
 * <h2>Vehicle identity is a choice of several, not one</h2>
 *
 * A shop has whatever the customer arrived with. Sometimes that is a fleet unit number on a
 * windscreen sticker, sometimes only a plate, occasionally a VIN. Requiring one specific identifier
 * would make the capability unusable in exactly the cases it exists for, so any one will do and the
 * codec sends what it was given.
 *
 * @param workorderId    the pos-workorder identity being authorized. This side's key throughout
 * @param vendorVehicleId the vendor's own vehicle id, when a lookup has already resolved one
 * @param licensePlate   as the customer presented it
 * @param vin            as the customer presented it
 * @param fleetNumber    the fleet's unit number, where known
 * @param contractId     the fleet contract claimed, when a lookup has already resolved one
 * @param odometerValue  reading at check-in, as recorded. A string because the vendor states it as
 *                       one and shops record it with and without units
 * @param lines          the work being requested
 */
public record WorkorderAuthorizationRequest(
        @NonNull UUID workorderId,
        @Nullable String vendorVehicleId,
        @Nullable String licensePlate,
        @Nullable String vin,
        @Nullable String fleetNumber,
        @Nullable String contractId,
        @Nullable String odometerValue,
        @NonNull List<Line> lines) {

    /**
     * One requested operation or part.
     *
     * @param reference   the part or operation reference, as the shop knows it
     * @param description free text for a human reading the request at the fleet
     * @param quantity    how many; never negative
     * @param tirePosition wheel position where the work is position-specific, as stated
     */
    public record Line(
            @Nullable String reference,
            @Nullable String description,
            int quantity,
            @Nullable String tirePosition) {

        public Line {
            // Genuine client-input validation reachable from the fleet-authorization @RequestBody
            // via toDomain(...) (#1694); typed rather than a bare IllegalArgumentException so it
            // cannot be confused with a server-side defect once the blanket handler is gone.
            if (quantity < 0) {
                throw new SupplierValidationException(
                        SupplierValidationException.VALIDATION_ERROR, "quantity must not be negative");
            }
        }
    }

    public WorkorderAuthorizationRequest {
        Objects.requireNonNull(workorderId, "workorderId must not be null");
        Objects.requireNonNull(lines, "lines must not be null");
        lines = List.copyOf(lines);
        if (!identifiesAVehicle(vendorVehicleId, licensePlate, vin, fleetNumber)) {
            // Refused here rather than sent, because a request with no vehicle cannot be authorized
            // by anyone and the vendor's rejection would arrive hours later as an opaque 400. Typed
            // rather than a bare IllegalArgumentException (#1694) so it cannot be confused with a
            // server-side defect once the blanket handler is gone; the controller documents this
            // exact 400.
            throw new SupplierValidationException(
                    SupplierValidationException.VALIDATION_ERROR,
                    "an authorization request must identify a vehicle by vendor id, plate, VIN or fleet number");
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
