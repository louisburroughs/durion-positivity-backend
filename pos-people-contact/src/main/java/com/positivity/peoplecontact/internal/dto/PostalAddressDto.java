package com.positivity.peoplecontact.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Structured, international postal address (FI-4, #1135). Only {@code line1} and
 * {@code countryCode} are required: {@code region} is a free-text administrative area (state,
 * province, prefecture, county) and {@code postalCode} is optional because several countries do
 * not use one. No country-specific format is enforced, so the same shape works for any
 * deployment locale.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Structured international postal address")
public class PostalAddressDto {

    @NotBlank(message = "line1 is required")
    @Size(max = 255)
    @Schema(
            description = "First address line (street address, PO box, company name)",
            example = "1-2-3 Ginza",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String line1;

    @Size(max = 255)
    @Schema(
            description = "Second address line (apartment, suite, unit, building, floor)",
            example = "Chuo-ku",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String line2;

    @Size(max = 120)
    @Schema(description = "City, town, or locality", example = "Tokyo", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String city;

    @Size(max = 120)
    @Schema(
            description = "Administrative area: state, province, prefecture, or county (free text)",
            example = "Tokyo",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String region;

    @Size(max = 20)
    @Schema(
            description = "Postal or ZIP code; omit for countries without one",
            example = "104-0061",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String postalCode;

    @NotBlank(message = "countryCode is required")
    @Pattern(regexp = "^[A-Za-z]{2}$", message = "countryCode must be an ISO 3166-1 alpha-2 code")
    @Schema(
            description = "ISO 3166-1 alpha-2 country code",
            example = "JP",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String countryCode;
}
