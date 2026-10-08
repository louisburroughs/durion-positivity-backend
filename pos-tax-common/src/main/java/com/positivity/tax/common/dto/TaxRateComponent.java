package com.positivity.tax.common.dto;

import com.positivity.tax.common.enums.TaxJurisdictionType;
import com.positivity.tax.common.enums.TaxType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import org.jspecify.annotations.Nullable;

/**
 * A single per-jurisdiction rate returned by the jurisdiction rate lookup (issue #1522).
 * <p>
 * {@code rate} is a <strong>decimal fraction</strong> (e.g. {@code 0.0725} for 7.25%),
 * matching {@link TaxCalculationResponse.JurisdictionTax#getRate()} — not the
 * percentage-point convention used by {@link TaxJurisdiction#getTaxRate()}. The codebase has
 * both conventions; callers of this DTO must not multiply or divide by 100.
 * <p>
 * {@code taxType} and {@code inputTaxRecoverable} are set only for a country with a tax-type
 * profile (CAP:550 S32a); for every other country, the United States included, both are
 * {@code null}.
 *
 * @param jurisdictionType    the level of government the rate applies at
 * @param rate                the tax rate as a decimal fraction
 * @param taxType             the configured tax type of this component; {@code null} when the
 *                            country has no tax-type profile or the code is unknown to this build
 * @param inputTaxRecoverable the configured placeholder recoverability of this tax type;
 *                            {@code null} when the country has no tax-type profile
 */
@Schema(name = "TaxRateComponent", description = "A single per-jurisdiction tax rate, expressed as a decimal fraction")
public record TaxRateComponent(
        @Schema(description = "Jurisdiction level the rate applies at", requiredMode = Schema.RequiredMode.REQUIRED)
        TaxJurisdictionType jurisdictionType,

        @Schema(
                description = "Tax rate as a decimal fraction (e.g. 0.0725 for 7.25%), not a percentage",
                example = "0.0725",
                requiredMode = Schema.RequiredMode.REQUIRED)
        BigDecimal rate,

        @Schema(
                description = "Configured tax type of this component; null for a country without a tax-type"
                        + " profile, and null when the value is unknown to the reader's build",
                example = "GST",
                nullable = true,
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @Nullable
        TaxType taxType,

        @Schema(
                description = "Configured placeholder recoverability of this tax type (held for expert advice);"
                        + " null for a country without a tax-type profile",
                example = "true",
                nullable = true,
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @Nullable
        Boolean inputTaxRecoverable) {}
