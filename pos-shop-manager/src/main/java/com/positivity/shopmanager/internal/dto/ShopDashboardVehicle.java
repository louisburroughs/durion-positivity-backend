package com.positivity.shopmanager.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Structured vehicle identity on a shop dashboard row (#1658).
 *
 * <p>Deliberately not {@code com.positivity.shared.dto.VehicleResponse}: that DTO is the vehicle
 * registry's own write/read contract and declares eight further fields as REQUIRED
 * ({@code accountId}, {@code vinNormalized}, {@code unitNumber}, {@code description},
 * {@code isActive}, {@code createdAt}, {@code updatedAt}, {@code version}). The local
 * {@code ext_vehicle} replica does not hold all of them, so emitting it here would either lie
 * about a required field or ship nulls through a contract that promises values. This record is the
 * four fields the dashboard actually shows, plus the id to link on.
 */
@Schema(description = "Vehicle identity for a dashboard row, from the local vehicle replica.")
public record ShopDashboardVehicle(
        @Schema(
                description = "Vehicle registry identifier.",
                example = "01960004-0000-7000-8000-0000000000a1",
                format = "uuid")
        @Nullable
        UUID vehicleId,

        @Schema(description = "Vehicle identification number.", example = "1HGCM82633A004352") @Nullable
        String vin,

        @Schema(description = "Model year.", example = "2024") @Nullable
        Integer year,

        @Schema(description = "Vehicle make.", example = "Ford") @Nullable
        String make,

        @Schema(description = "Vehicle model.", example = "F-150") @Nullable
        String model) {}
