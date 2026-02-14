package com.positivity.tax.internal.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

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
public class TaxCalculationRequest {

    /**
     * List of line items to calculate tax for.
     */
    @NotEmpty(message = "At least one line item is required")
    @Valid
    private List<TaxLineItem> lineItems;

    /**
     * Ship-to or service location postal code.
     * <p>
     * Primary field used to determine tax jurisdiction.
     */
    @NotBlank(message = "Postal code is required")
    private String postalCode;

    /**
     * Ship-to or service location country code (ISO 3166-1 alpha-2).
     * <p>
     * Defaults to "US" if not provided.
     */
    @Builder.Default
    private String countryCode = "US";

    /**
     * Ship-to or service location state/province code.
     */
    private String stateCode;

    /**
     * Ship-to or service location city name.
     */
    private String city;

    /**
     * Ship-to or service location street address.
     */
    private String address;

    /**
     * Optional customer ID for tax exemption lookup.
     */
    private String customerId;

    /**
     * Optional transaction date (ISO 8601 format).
     * <p>
     * If not provided, current date is used.
     */
    private String transactionDate;

    /**
     * Optional reference ID for the source transaction (e.g., estimate ID, invoice ID).
     */
    private String referenceId;
}
