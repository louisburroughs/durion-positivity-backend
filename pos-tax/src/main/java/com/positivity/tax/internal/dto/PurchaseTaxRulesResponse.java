package com.positivity.tax.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;

/**
 * Response of the purchase-tax rules read ({@code GET /v1/tax/purchase-rules}, CAP:550 S43, AW44).
 * <p>
 * Projects a country's configured purchase-tax rules: whether a vendor bill charging tax on goods for resale is held
 * for a person, and whether a bill stating no tax self-assesses (use) tax on its expense lines. Every value is
 * configuration held for expert advice, never tax law, so {@code source} is always {@code STUB}. A country with no
 * configured rules answers {@code configured = false}, {@code ALLOW} and {@code false}: a defined answer, not a
 * missing one.
 *
 * @param countryCode               the queried country, echoed
 * @param asOf                      the date the rules apply on (the stub does not date them)
 * @param source                    always {@code STUB}
 * @param configured                whether the country configures purchase-tax rules
 * @param taxOnResaleGoods          {@code HOLD} or {@code ALLOW}
 * @param selfAssessUntaxedExpenses whether an untaxed expense bill self-assesses tax
 */
@Schema(name = "TaxPurchaseRulesResponse", description = "The purchase-tax rules configured for one country on a date")
public record PurchaseTaxRulesResponse(
        @Schema(
                description = "Country code in ISO 3166-1 alpha-2 format, echoed from the request",
                example = "ZZ",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String countryCode,

        @Schema(
                description = "The date the rules apply on, ISO-8601; today when the request gave none",
                example = "2026-10-08",
                requiredMode = Schema.RequiredMode.REQUIRED)
        LocalDate asOf,

        @Schema(
                description = "Origin of the answer; always STUB, because every value is a placeholder",
                example = "STUB",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String source,

        @Schema(
                description = "Whether the country configures purchase-tax rules; false answers ALLOW and false",
                example = "true",
                requiredMode = Schema.RequiredMode.REQUIRED)
        boolean configured,

        @Schema(
                description = "HOLD when a vendor bill charging tax on goods for resale is held for a person, else"
                        + " ALLOW",
                example = "HOLD",
                allowableValues = {"HOLD", "ALLOW"},
                requiredMode = Schema.RequiredMode.REQUIRED)
        String taxOnResaleGoods,

        @Schema(
                description = "Whether a vendor bill that states no tax self-assesses (use) tax on its expense lines",
                example = "true",
                requiredMode = Schema.RequiredMode.REQUIRED)
        boolean selfAssessUntaxedExpenses) {}
