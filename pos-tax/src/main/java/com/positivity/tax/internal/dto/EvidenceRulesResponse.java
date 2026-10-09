package com.positivity.tax.internal.dto;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Response of the evidence-rules read ({@code GET /v1/tax/evidence-rules}, CAP:550 S32b, AW53).
 * <p>
 * Projects the evidence rules a country's profile configures that are in effect on a date. Every value
 * is configuration held for expert advice, never tax law, so {@code source} is always {@code STUB}. A
 * country with no rule, or no profile, answers an empty list.
 *
 * @param countryCode the queried country, echoed
 * @param asOf        the date the rules are in effect on
 * @param currency    the profile's ISO 4217 currency, the currency of every amount; {@code null} with no
 *                    profile
 * @param rules       the rules in effect, in configured order
 * @param source      always {@code STUB}
 * @param supplierRegistrationRegime the regime whose registration a supplier's number is
 *                    ({@code pos.tax.countries.<country>.supplier-registration-regime}), the registration a
 *                    {@code SUPPLIER_REGISTRATION_NUMBER} rule asks for; {@code null} when the country names none
 *                    (CAP:550 S32d, AW53: a vendor bill's evidence is checked against the vendor's copy)
 */
@Schema(name = "TaxEvidenceRulesResponse", description = "The evidence rules configured for one country on a date")
public record EvidenceRulesResponse(
        @Schema(
                description = "Country code in ISO 3166-1 alpha-2 format, echoed from the request",
                example = "ZZ",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String countryCode,

        @Schema(
                description = "Date the rules are in effect on: the requested asOf, or today when it was omitted",
                example = "2026-10-08",
                requiredMode = Schema.RequiredMode.REQUIRED)
        LocalDate asOf,

        @Schema(
                description = "ISO 4217 currency of every amount, the country profile's; null when the country has"
                        + " no profile",
                example = "EUR",
                nullable = true,
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @Nullable
        String currency,

        @Schema(
                description = "Rules in effect on asOf, in configured order; empty when the country has none",
                requiredMode = Schema.RequiredMode.REQUIRED)
        List<EvidenceRuleEntry> rules,

        @Schema(
                description = "Origin of the answer; always STUB, because every value is a placeholder",
                example = "STUB",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String source,

        @Schema(
                description = "Regime whose registration a supplier's number is, the registration a"
                        + " SUPPLIER_REGISTRATION_NUMBER rule asks the supplier to hold; null when the country names"
                        + " none",
                example = "REGIME_1",
                nullable = true,
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @Nullable
        String supplierRegistrationRegime) {

    /**
     * One evidence rule.
     *
     * @param rule          the evidence required
     * @param fromAmount    the amount, tax included, from which it applies
     * @param appliesTo     the document types it applies to
     * @param effectiveFrom inclusive first date; {@code null} for always
     * @param effectiveTo   inclusive last date; {@code null} for open-ended
     */
    @Schema(name = "TaxEvidenceRuleEntry", description = "From which amount a document type needs a piece of evidence")
    public record EvidenceRuleEntry(
            @Schema(
                    description = "Evidence the rule requires",
                    example = "SUPPLIER_REGISTRATION_NUMBER",
                    allowableValues = {"SUPPLIER_REGISTRATION_NUMBER"},
                    requiredMode = Schema.RequiredMode.REQUIRED)
            String rule,

            @Schema(
                    description = "Document total, tax included and in the response currency, from which the rule"
                            + " applies (inclusive)",
                    example = "100.00",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            BigDecimal fromAmount,

            @ArraySchema(
                    arraySchema =
                            @Schema(
                                    description = "Document types the rule applies to",
                                    example = "[\"DRAWER_RECEIPT\", \"VENDOR_BILL\"]",
                                    requiredMode = Schema.RequiredMode.REQUIRED),
                    schema = @Schema(allowableValues = {"DRAWER_RECEIPT", "VENDOR_BILL"}))
            List<String> appliesTo,

            @Schema(
                    description = "Inclusive first date the rule is in effect; null when it has no start",
                    example = "2026-01-01",
                    nullable = true,
                    requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            @Nullable
            LocalDate effectiveFrom,

            @Schema(
                    description = "Inclusive last date the rule is in effect; null when it is open-ended",
                    example = "2026-12-31",
                    nullable = true,
                    requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            @Nullable
            LocalDate effectiveTo) {}
}
