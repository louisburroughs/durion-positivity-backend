package com.positivity.referencemock.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * One labor-time line of a feed chunk. Normative provider-contract shape: field names are
 * consumed verbatim by the pos-catalog ingest side. {@code null} vehicle fields are wildcards.
 *
 * @param providerOperationCode vendor operation code
 * @param vehicleYear model year string, or null (wildcard)
 * @param make vehicle make, or null (wildcard)
 * @param model vehicle model, or null (wildcard)
 * @param submodel vehicle submodel, or null (wildcard)
 * @param engineCode engine code, or null (wildcard)
 * @param hours decimal hours in tenths
 * @param timeType RETAIL_FLAT_RATE, OEM_WARRANTY or MANUFACTURER_INSTALL
 * @param overlapGroup shared-setup overlap group, or null
 * @param includedOperations DURION operation codes whose time is included in this one
 * @param publishedAt guide publication date
 */
@Schema(description = "One labor-time line of a feed chunk; null vehicle fields are wildcards.")
public record FeedLineDto(
        String providerOperationCode,
        String vehicleYear,
        String make,
        String model,
        String submodel,
        String engineCode,
        BigDecimal hours,
        String timeType,
        String overlapGroup,
        List<String> includedOperations,
        LocalDate publishedAt) {

    public FeedLineDto {
        includedOperations = includedOperations == null ? List.of() : List.copyOf(includedOperations);
    }
}
