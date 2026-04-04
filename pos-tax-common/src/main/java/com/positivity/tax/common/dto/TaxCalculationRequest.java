package com.positivity.tax.common.dto;

import com.positivity.tax.common.enums.TaxReferenceType;
import com.positivity.tax.common.validation.IsoCountryCode;
import com.positivity.tax.common.validation.IsoCurrencyCode;
import com.positivity.tax.common.validation.ValidSubdivisionForCountry;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

/**
 * Request for tax calculation.
 * <p>
 * Contains the line items to be taxed and the location information
 * needed to determine applicable tax jurisdictions.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(name = "TaxCalculationRequest", description = "International tax calculation request payload", example = """
        {
          "lineItems": [
            {
              "lineItemId": "1",
              "description": "Oil Change Service",
              "quantity": 1,
              "unitPrice": 89.99,
              "taxExempt": false
            }
          ],
          "destinationAddress": {
            "countryCode": "US",
            "regionCode": "CA",
            "city": "Los Angeles",
            "postalCode": "90001",
            "line1": "123 Main St"
          },
          "currencyCode": "USD",
          "locale": "en-US",
          "referenceId": "550e8400-e29b-41d4-a716-446655440000",
          "referenceType": "ESTIMATE"
        }
        """)
public class TaxCalculationRequest {

    /**
     * List of line items to calculate tax for.
     */
    @NotEmpty(message = "At least one line item is required")
    @Valid
    @Schema(description = "Line items to calculate tax for", requiredMode = Schema.RequiredMode.REQUIRED)
    private List<TaxLineItem> lineItems;

    /**
     * Internationalized destination address for tax determination.
     * <p>
     * This structure supports global addressing standards.
     */
    @NotNull(message = "destinationAddress is required")
    @Valid
    @Schema(description = "Destination address used to determine applicable tax jurisdictions", requiredMode = Schema.RequiredMode.REQUIRED)
    private TaxAddress destinationAddress;

    /**
     * Transaction currency (ISO 4217 alpha-3), e.g., USD, EUR, JPY.
     */
    @Builder.Default
    @IsoCurrencyCode(message = "currencyCode must be a valid ISO 4217 code")
    @Schema(description = "Transaction currency in ISO 4217 alpha-3 format", example = "USD")
    private String currencyCode = "USD";

    /**
     * BCP 47 locale tag requested by the client (e.g., en-US, fr-CA, es-MX).
     */
    @Schema(description = "BCP 47 locale tag for localization preferences", example = "en-US")
    private String locale;

    /**
     * Optional customer ID for tax exemption lookup.
     */
    @Schema(description = "Optional customer identifier for exemption and tax profile lookups", example = "CUST-100234")
    private String customerId;

    /**
     * Optional transaction date (ISO 8601 format).
     * <p>
     * If not provided, current date is used.
     */
    @Schema(description = "Optional transaction date/time in ISO 8601 format", example = "2026-02-21T09:30:00Z")
    private String transactionDate;

    /**
     * Optional reference ID for the source transaction (e.g., estimate ID, invoice
     * ID).
     */
    @Schema(description = "Optional reference identifier for the source transaction", example = "550e8400-e29b-41d4-a716-446655440000")
    private UUID referenceId;
    /**
     * Optional reference type for the source transaction (e.g., estimate, invoice).
     */
    @Schema(description = "Optional source transaction type associated with referenceId", example = "ESTIMATE")
    private TaxReferenceType referenceType;

    /** Convenience accessor used by internal tax logic. */
    public String getPostalCode() {
        if (destinationAddress == null) {
            return null;
        }
        return destinationAddress.getPostalCode();
    }

    /** Convenience accessor used by internal tax logic. */
    public String getCountryCode() {
        if (destinationAddress == null) {
            return null;
        }
        return destinationAddress.getCountryCode();
    }

    /** Convenience accessor used by internal tax logic. */
    public String getStateCode() {
        if (destinationAddress == null) {
            return null;
        }
        return destinationAddress.getRegionCode();
    }

    /** Convenience accessor used by internal tax logic. */
    public String getCity() {
        if (destinationAddress == null) {
            return null;
        }
        return destinationAddress.getCity();
    }

    /** Convenience accessor used by internal tax logic. */
    public String getAddress() {
        if (destinationAddress == null) {
            return null;
        }
        return destinationAddress.getLine1();
    }

    /**
     * International address model for tax requests.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(name = "TaxAddress", description = "International destination address")
    @ValidSubdivisionForCountry
    public static class TaxAddress {
        /**
         * Country code in ISO 3166-1 alpha-2 format.
         */
        @NotBlank(message = "destinationAddress.countryCode is required")
        @IsoCountryCode(message = "destinationAddress.countryCode must be a valid ISO 3166-1 alpha-2 code")
        @Schema(type = "string", description = "Country code in ISO 3166-1 alpha-2 format", example = "US")
        private String countryCode;

        /**
         * Region/subdivision code (typically ISO 3166-2 subdivision part, e.g., CA, TX,
         * ON).
         */
        @Schema(type = "string", description = "Region/subdivision code (typically ISO 3166-2 subdivision part)", example = "CA")
        private String regionCode;

        /**
         * Locality/city/municipality name.
         */
        @Schema(type = "string", description = "Locality/city/municipality name", example = "Los Angeles")
        private String city;

        /**
         * Postal or ZIP code.
         */
        @NotBlank(message = "destinationAddress.postalCode is required")
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

        public String getCountryCode() {
            return countryCode;
        }

        public String getRegionCode() {
            return regionCode;
        }

        public String getCity() {
            return city;
        }

        public String getPostalCode() {
            return postalCode;
        }

        public String getLine1() {
            return line1;
        }

        public String getLine2() {
            return line2;
        }
    }
}
