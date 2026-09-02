package com.positivity.referencemock.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * One published labor time for (vehicle, provider operation), with the relationship data that
 * makes workorder summation honest. Normative provider-contract shape: field names are consumed
 * verbatim by the pos-catalog {@code mockguide} adapter.
 *
 * @param providerOperationCode vendor operation code the time belongs to
 * @param hours decimal hours in tenths
 * @param timeType RETAIL_FLAT_RATE, OEM_WARRANTY or MANUFACTURER_INSTALL
 * @param includedOperations DURION operation codes whose time is included in this one
 * @param overlapGroup shared-setup overlap group, or null
 * @param sourceRevision feed revision that published this time
 * @param publishedAt guide publication date
 * @param notes optional vendor commentary, or null
 */
@Schema(description = "One published labor time for the requested vehicle and provider operation.")
public record ProviderLaborTimeDto(
        @Schema(description = "Vendor operation code", example = "MG-BRAKE-PAD-FRONT")
        String providerOperationCode,

        @Schema(description = "Decimal hours in tenths", example = "1.5")
        BigDecimal hours,

        @Schema(description = "Time type", example = "RETAIL_FLAT_RATE")
        String timeType,

        @Schema(description = "DURION operation codes whose time is included in this one")
        List<String> includedOperations,

        @Schema(description = "Shared-setup overlap group, null when none", example = "WHEEL-OFF")
        String overlapGroup,

        @Schema(description = "Feed revision that published this time", example = "2026-09-01")
        String sourceRevision,

        @Schema(description = "Guide publication date", example = "2026-09-01")
        LocalDate publishedAt,

        @Schema(description = "Optional vendor commentary") String notes) {

    public ProviderLaborTimeDto {
        includedOperations = includedOperations == null ? List.of() : List.copyOf(includedOperations);
    }
}
