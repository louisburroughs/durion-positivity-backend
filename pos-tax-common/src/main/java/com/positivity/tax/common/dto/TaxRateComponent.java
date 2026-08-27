package com.positivity.tax.common.dto;

import com.positivity.tax.common.enums.TaxJurisdictionType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;

/**
 * A single per-jurisdiction rate returned by the jurisdiction rate lookup (issue #1522).
 * <p>
 * {@code rate} is a <strong>decimal fraction</strong> (e.g. {@code 0.0725} for 7.25%),
 * matching {@link TaxCalculationResponse.JurisdictionTax#getRate()} — not the
 * percentage-point convention used by {@link TaxJurisdiction#getTaxRate()}. The codebase has
 * both conventions; callers of this DTO must not multiply or divide by 100.
 *
 * @param jurisdictionType the level of government the rate applies at
 * @param rate             the tax rate as a decimal fraction
 */
@Schema(name = "TaxRateComponent", description = "A single per-jurisdiction tax rate, expressed as a decimal fraction")
public record TaxRateComponent(
        @Schema(description = "Jurisdiction level the rate applies at", requiredMode = Schema.RequiredMode.REQUIRED)
        TaxJurisdictionType jurisdictionType,

        @Schema(
                description = "Tax rate as a decimal fraction (e.g. 0.0725 for 7.25%), not a percentage",
                example = "0.0725",
                requiredMode = Schema.RequiredMode.REQUIRED)
        BigDecimal rate) {}
