package com.positivity.tax.internal.dto;

import com.positivity.tax.common.validation.IsoCurrencyCode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Request of the stated-tax plausibility check ({@code POST /v1/tax/plausibility-checks}, CAP:550 S32b,
 * AW55).
 * <p>
 * {@code supplierRegistrationNumber} carries no bean-validation constraint, so no binding error can echo
 * it, and {@link #toString()} redacts it: the number is never logged, whatever logs the request.
 *
 * @param countryCode                the receipt's country
 * @param regionCode                 the receipt's region (subdivision)
 * @param postalCode                 the receipt's postal code
 * @param city                       the receipt's city, optional
 * @param asOf                       the receipt's business date; today when omitted
 * @param currencyCode               the receipt's currency; must be the country profile's
 * @param receiptTotal               the receipt total, tax included
 * @param statedTaxes                the tax amounts stated on the receipt, each regime at most once
 * @param supplierRegistrationNumber the supplier's registration number, optional; never echoed or logged
 */
@Schema(name = "TaxPlausibilityCheckRequest", description = "A receipt's total and the tax amounts stated on it")
public record PlausibilityCheckRequest(
        @Schema(
                description = "Country of the receipt, ISO 3166-1 alpha-2 in upper case",
                example = "ZZ",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank
        @Pattern(regexp = "^[A-Z]{2}$", message = "must be two upper-case letters")
        String countryCode,

        @Schema(
                description = "Region (subdivision) of the receipt, 1 to 3 letters or digits",
                example = "Z1",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank
        @Pattern(regexp = "^[A-Za-z0-9]{1,3}$", message = "must be 1 to 3 letters or digits")
        String regionCode,

        @Schema(
                description = "Postal code of the receipt",
                example = "Z1Z 1Z1",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank
        @Size(max = 20)
        String postalCode,

        @Schema(
                description = "City of the receipt",
                example = "Springfield",
                nullable = true,
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @Size(max = 100)
        @Nullable
        String city,

        @Schema(
                description = "Business date of the receipt (ISO-8601); today when omitted",
                example = "2026-10-08",
                nullable = true,
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @Nullable
        LocalDate asOf,

        @Schema(
                description = "ISO 4217 currency of every amount; must be the country profile's currency",
                example = "EUR",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank
        @IsoCurrencyCode(message = "must be a valid ISO 4217 code")
        String currencyCode,

        @Schema(
                description = "Receipt total, tax included, above zero and at most the currency's decimals",
                example = "150.00",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull
        @Positive
        BigDecimal receiptTotal,

        @Schema(
                description = "Tax amounts stated on the receipt, one per regime at most; absent or empty means none",
                nullable = true,
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @Size(max = 32)
        @Nullable
        List<@Valid @NotNull StatedTax> statedTaxes,

        @Schema(
                description = "Supplier's registration number as printed; it is checked against the country's"
                        + " supplier regime shape and is never echoed, logged or stored",
                nullable = true,
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @Nullable
        String supplierRegistrationNumber) {

    /**
     * Redacts the supplier's registration number: a logged request never carries it.
     *
     * @return the request with the number replaced by whether one was sent
     */
    @Override
    public String toString() {
        return "PlausibilityCheckRequest[countryCode=" + countryCode + ", regionCode=" + regionCode
                + ", asOf=" + asOf
                + ", currencyCode=" + currencyCode + ", receiptTotal=" + receiptTotal + ", statedTaxes="
                + statedTaxes
                + ", supplierRegistrationNumberProvided=" + (supplierRegistrationNumber != null) + "]";
    }

    /**
     * One tax amount stated on a receipt.
     *
     * @param regime the regime the amount is stated under
     * @param amount the amount, zero or more
     */
    @Schema(name = "TaxStatedTax", description = "One tax amount stated on a receipt, keyed by regime")
    public record StatedTax(
            @Schema(
                    description = "Regime code the amount is stated under; it must be declared for the country",
                    example = "REGIME_1",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            @NotBlank
            @Pattern(
                    regexp = "^[A-Z0-9_]{1,32}$",
                    message = "must be 1 to 32 upper-case letters, digits or underscores")
            String regime,

            @Schema(
                    description = "Amount stated, zero or more, at most the currency's decimals",
                    example = "19.50",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            @NotNull
            @PositiveOrZero
            BigDecimal amount) {}
}
