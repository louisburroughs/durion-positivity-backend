package com.positivity.location.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.location.internal.enums.BayType;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One bay type with the specialty services a bay of that type is defaulted to (CAP-325 D14.1,
 * #2247).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "A bay type with the specialty services a new bay of this type is defaulted to")
public class BayTypeResponse {

    @Schema(description = "Bay type classification", example = "ALIGNMENT", requiredMode = REQUIRED)
    @NotNull
    private BayType bayType;

    @Schema(
            description = "Whether a bay of this type is eligible for general (non-specialty) work by default;"
                    + " false only for WASH_DETAIL",
            example = "true",
            requiredMode = REQUIRED)
    private boolean acceptsGeneralWork;

    @ArraySchema(
            arraySchema =
                    @Schema(
                            description = "Catalog operation codes a bay of this type is given when it is created, or"
                                    + " its type is changed, without explicit serviceCapabilityCodes; sorted"
                                    + " ascending, empty when the type has no default specialties",
                            requiredMode = REQUIRED),
            schema = @Schema(example = "WHEEL-ALIGNMENT-4-WHEEL"))
    @NotNull
    private List<String> defaultServiceCapabilityCodes;

    @Schema(
            description = "The same defaults as defaultServiceCapabilityCodes, in the same order, each with its"
                    + " catalog service name",
            requiredMode = REQUIRED)
    @NotNull
    private List<DefaultServiceEntry> defaultServices;

    /** A default specialty service of a bay type, named from the catalog service replica. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @Schema(name = "BayTypeDefaultService", description = "A default specialty service of a bay type")
    public static class DefaultServiceEntry {

        @Schema(
                description = "Catalog operation code (UPPER-DASH, ADR-0059)",
                example = "WHEEL-ALIGNMENT-4-WHEEL",
                requiredMode = REQUIRED)
        @NotNull
        private String operationCode;

        @Schema(
                description = "Catalog service name as replicated from pos-catalog; null when the catalog service"
                        + " carries no name",
                example = "4-Wheel Alignment",
                nullable = true)
        private String name;
    }
}
