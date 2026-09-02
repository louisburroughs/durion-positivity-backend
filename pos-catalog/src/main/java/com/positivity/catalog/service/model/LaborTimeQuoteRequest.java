package com.positivity.catalog.service.model;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * A labor-time resolution query (#1569 Phase 1, ADR-0058 §5): which catalog service operation is
 * being quoted, on which vehicle, and which time class the workorder wants.
 *
 * <p>Vehicle fields follow the null-as-wildcard convention: an unknown field widens the match
 * rather than failing it, and the response's {@code matchGrade} says how confident the answer
 * is. The vocabulary is pos-vehicle-fitment's — the caller resolves the vehicle once and passes
 * the resolved strings (sourcing plan §4.3).
 *
 * @param serviceId the catalog service (operation) being quoted
 * @param vehicleYear year or range like {@code 2019-2023}; null = unknown
 * @param make vehicle make; null = unknown
 * @param model vehicle model; null = unknown
 * @param submodel submodel/trim; null = unknown
 * @param engineCode engine code; null = unknown
 * @param preferredTimeType {@code RETAIL_FLAT_RATE | OEM_WARRANTY | MANUFACTURER_INSTALL |
 *     DURION_STANDARD}; null = retail-first default ordering
 */
@Schema(
        name = "LaborTimeQuoteRequest",
        description = "Which service operation is being quoted, on which vehicle (null fields widen the"
                + " match), and which time class the workorder wants (null = retail-first).")
public record LaborTimeQuoteRequest(
        @Schema(description = "Catalog service being quoted.") @NotNull @NonNull
        UUID serviceId,

        @Schema(description = "Model year or range; null = unknown.", example = "2019-2023") @Size(max = 16) @Nullable
        String vehicleYear,

        @Schema(description = "Vehicle make; null = unknown.", example = "Honda") @Size(max = 64) @Nullable
        String make,

        @Schema(description = "Vehicle model; null = unknown.", example = "Civic") @Size(max = 64) @Nullable
        String model,

        @Schema(description = "Submodel or trim; null = unknown.", example = "EX") @Size(max = 64) @Nullable
        String submodel,

        @Schema(description = "Engine code; null = unknown.", example = "K20C2") @Size(max = 64) @Nullable
        String engineCode,

        @Schema(
                description = "Preferred time class; null = retail-first default ordering.",
                example = "RETAIL_FLAT_RATE",
                allowableValues = {"RETAIL_FLAT_RATE", "OEM_WARRANTY", "MANUFACTURER_INSTALL", "DURION_STANDARD"})
        @Nullable
        String preferredTimeType) {}
