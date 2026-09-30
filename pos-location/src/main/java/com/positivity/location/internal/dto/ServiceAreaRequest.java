package com.positivity.location.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request payload for service area endpoints.
 *
 * Issue: #76
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "Request payload for creating or updating a service area")
public class ServiceAreaRequest {

    @Schema(
            description = "Display name of the service area; unique per tenant, at most 255 characters",
            example = "North Metro",
            maxLength = 255,
            requiredMode = REQUIRED)
    @NotBlank
    @Size(min = 1, max = 255)
    private String name;

    @Schema(
            description = "Description of the service area",
            example = "Northern metropolitan coverage zone",
            maxLength = 255,
            requiredMode = NOT_REQUIRED)
    @Size(max = 255)
    private String description;

    @Schema(description = "Whether the service area is active", example = "true", requiredMode = NOT_REQUIRED)
    private Boolean active;

    @Schema(description = "Postal codes included in the service area", requiredMode = NOT_REQUIRED)
    @Valid
    private List<PostalCodeEntry> postalCodes;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @Schema(description = "A postal code entry within a service area")
    public static class PostalCodeEntry {

        @Schema(
                description = "Postal or ZIP code, at most 20 characters",
                example = "62704",
                maxLength = 20,
                requiredMode = REQUIRED)
        @NotBlank
        @Size(min = 1, max = 20)
        private String postalCode;

        @Schema(
                description = "ISO 3166-1 alpha-2 country code, at most 2 characters",
                example = "US",
                maxLength = 2,
                requiredMode = REQUIRED)
        @NotBlank
        @Size(min = 1, max = 2)
        private String countryCode;
    }
}
