package com.positivity.tax.common.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.positivity.tax.common.enums.TaxJurisdictionType;
import com.positivity.tax.common.validation.IsoCountryCode;
import com.positivity.tax.common.validation.ValidSubdivisionForCountry;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Represents a tax jurisdiction with its applicable rates.
 * <p>
 * A jurisdiction can be a country, state/province, county, city, or special
 * district.
 * Multiple jurisdictions can apply to a single transaction (e.g., state +
 * county + city).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(name = "TaxJurisdiction", description = "Tax jurisdiction entry with normalized international location fields and calculated tax values")
@ValidSubdivisionForCountry
public class TaxJurisdiction {

    /**
     * Country code in ISO 3166-1 alpha-2 format.
     */
    @NotNull(message = "countryCode is required")
    @Pattern(regexp = "^[A-Z]{2}$", message = "countryCode must be ISO 3166-1 alpha-2")
    @IsoCountryCode(message = "countryCode must be a valid ISO 3166-1 alpha-2 code")
    @Schema(type = "string", description = "Country code in ISO 3166-1 alpha-2 format", example = "US", requiredMode = Schema.RequiredMode.REQUIRED)
    private String countryCode;

    /**
     * Region/subdivision code (ISO 3166-2 subdivision part where applicable).
     */
    @Schema(type = "string", description = "Region/subdivision code (ISO 3166-2 subdivision part)", example = "CA")
    private String regionCode;

    /**
     * Locality/city/municipality name.
     */
    @Schema(type = "string", description = "Locality/city/municipality name", example = "Los Angeles")
    private String city;

    /**
     * Postal or ZIP code.
     */
    @Schema(type = "string", description = "Postal/ZIP code", example = "\"90001\"")
    private String postalCode;

    /**
     * Primary address line.
     */
    @Schema(type = "string", description = "Primary street address line", example = "\"123 Main St\"")
    private String line1;

    /**
     * Secondary address line (optional).
     */
    @Schema(type = "string", description = "Secondary address line", example = "\"Suite 200\"")
    private String line2;

    /**
     * Tax rate as a percentage (e.g., 7.25 for 7.25%).
     */
    @NotNull(message = "taxRate is required")
    @PositiveOrZero(message = "taxRate must be >= 0")
    @Schema(description = "Tax rate as percentage points", example = "7.25", requiredMode = Schema.RequiredMode.REQUIRED)
    private BigDecimal taxRate;

    /**
     * Type of jurisdiction (e.g., "STATE", "COUNTY", "CITY", "DISTRICT").
     */
    @NotNull(message = "jurisdictionType is required")
    @Schema(description = "Jurisdiction type", example = "STATE", requiredMode = Schema.RequiredMode.REQUIRED)
    private TaxJurisdictionType jurisdictionType;

    /**
     * Internationalization key for this jurisdiction type.
     * <p>
     * Clients can use this key to localize human-readable labels.
     */
    @JsonProperty("jurisdictionTypeI18nKey")
    @Schema(description = "I18n key for localized jurisdiction type labels", example = "tax.jurisdiction.type.state")
    public String getJurisdictionTypeI18nKey() {
        return jurisdictionType != null ? jurisdictionType.i18nKey() : null;
    }

    /**
     * Tax amount calculated for this jurisdiction.
     */
    @NotNull(message = "taxAmount is required")
    @PositiveOrZero(message = "taxAmount must be >= 0")
    @Schema(description = "Calculated tax amount for this jurisdiction", example = "6.53", requiredMode = Schema.RequiredMode.REQUIRED)
    private BigDecimal taxAmount;
}
