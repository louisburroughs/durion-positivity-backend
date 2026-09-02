package com.positivity.catalog.internal.spi.model;

import org.jspecify.annotations.Nullable;

/**
 * Normalized vehicle identity for a labor-time lookup (sourcing plan §3.3/§4.3). Field
 * vocabulary matches pos-vehicle-fitment ({@code Make.name}, {@code Model.name},
 * {@code engineType}, {@code submodel}); a {@code null} field is a wildcard, mirroring the
 * {@code service_labor_standard} convention.
 *
 * @param vehicleYear single year or a range like {@code 2019-2023}
 * @param make vehicle make
 * @param model vehicle model
 * @param submodel submodel or trim
 * @param engineCode engine code
 */
public record VehicleKey(
        @Nullable String vehicleYear,
        @Nullable String make,
        @Nullable String model,
        @Nullable String submodel,
        @Nullable String engineCode) {

    /** A key with every field wild — matches only wildcard rows, e.g. diagnostic blocks. */
    public static VehicleKey any() {
        return new VehicleKey(null, null, null, null, null);
    }
}
