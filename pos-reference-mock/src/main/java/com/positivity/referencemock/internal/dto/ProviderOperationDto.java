package com.positivity.referencemock.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One vendor operation applicable to the requested vehicle. Normative provider-contract shape:
 * field names are consumed verbatim by the pos-catalog {@code mockguide} adapter.
 *
 * @param providerOperationCode the vendor's operation code, e.g. {@code MG-BRAKE-PAD-FRONT}
 * @param name human-readable operation name
 * @param category vendor category: REPAIR, MAINTENANCE, DIAGNOSTIC or TIRE_SERVICE
 */
@Schema(description = "One vendor operation applicable to the requested vehicle.")
public record ProviderOperationDto(
        @Schema(description = "Vendor operation code", example = "MG-BRAKE-PAD-FRONT")
        String providerOperationCode,

        @Schema(description = "Human-readable operation name", example = "Brake Pad Replacement - Front")
        String name,

        @Schema(description = "Vendor operation category", example = "REPAIR")
        String category) {}
