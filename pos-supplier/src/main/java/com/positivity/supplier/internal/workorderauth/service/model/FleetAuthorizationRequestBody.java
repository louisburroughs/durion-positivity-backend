package com.positivity.supplier.internal.workorderauth.service.model;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A request to have a fleet program authorize work (CAP-323 #1229).
 *
 * <p>No single vehicle identifier is marked required, because a shop has whatever the customer
 * arrived with — sometimes a fleet unit number on a sticker, sometimes only a plate. That at least
 * one must be present is enforced in the domain, which is where the rule can be stated once for
 * every caller rather than repeated per field.
 */
@Schema(description = "A request to have a fleet program authorize the work on a workorder")
public record FleetAuthorizationRequestBody(
        @Schema(description = "Workorder the authorization is for", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull
        UUID workorderId,

        @Schema(description = "The vendor's own vehicle id, when a lookup has already resolved one") @Nullable
        String vendorVehicleId,

        @Schema(description = "License plate as presented") @Nullable
        String licensePlate,

        @Schema(description = "VIN as presented") @Nullable String vin,

        @Schema(description = "The fleet's own unit number") @Nullable
        String fleetNumber,

        @Schema(description = "Fleet contract claimed, when known") @Nullable
        String contractId,

        @Schema(description = "Odometer reading at check-in, as recorded") @Nullable
        String odometerValue,

        @Schema(description = "The work being requested") @Nullable
        List<Line> lines) {

    /** One requested operation or part. */
    @Schema(description = "One requested operation or part")
    public record Line(
            @Schema(description = "Part or operation reference as the shop knows it") @Nullable
            String reference,

            @Schema(description = "Free text for a human reading the request at the fleet") @Nullable
            String description,

            @Schema(description = "How many") @PositiveOrZero
            int quantity,

            @Schema(description = "Wheel position, where the work is position-specific") @Nullable
            String tirePosition) {}

    /** The requested lines, never null. */
    public List<Line> linesOrEmpty() {
        return lines == null ? List.of() : List.copyOf(lines);
    }
}
