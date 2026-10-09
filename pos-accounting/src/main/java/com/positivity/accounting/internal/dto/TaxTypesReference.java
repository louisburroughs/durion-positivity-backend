package com.positivity.accounting.internal.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * pos-tax's tax types and regimes of one country ({@code GET /v1/tax/tax-types}, CAP:550 S32a), as pos-accounting
 * reads them through {@code TaxReferenceClient}: never served as is by an accounting endpoint ({@link
 * TaxRegimesResponse} is the front door's shape, CAP:550 #2659). Every value is pos-tax configuration held for expert
 * advice ({@code source = STUB}); no code here names a tax type or regime.
 *
 * <p>Every field is nullable as read, so an answer missing a required field can be told from an empty one: the reader
 * treats a missing list as unreadable, never as "none configured" (ADR-0017).
 *
 * @param countryCode the country, echoed
 * @param currency    the country's configured currency; null with no profile (not served by the front door)
 * @param taxTypes    the declared tax types, in configured order; empty with no profile
 * @param regimes     the declared regimes, in configured order; empty with no profile
 * @param source      always {@code STUB}
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TaxTypesReference(
        @Nullable String countryCode,
        @Nullable String currency,
        @Nullable List<TaxType> taxTypes,
        @Nullable List<Regime> regimes,
        @Nullable String source) {

    /**
     * One declared tax type.
     *
     * @param taxType             the configured tax-type code
     * @param regime              the regime it is registered and recovered under; null for none
     * @param jurisdictionType    the jurisdiction level it is levied at
     * @param inputTaxRecoverable the placeholder recoverability (not served by the front door)
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TaxType(
            @Nullable String taxType,
            @Nullable String regime,
            @Nullable String jurisdictionType,
            boolean inputTaxRecoverable) {}

    /**
     * One declared registration and recovery regime.
     *
     * @param regime  the regime code
     * @param regions the region codes it covers; empty means the whole country
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Regime(@Nullable String regime, @Nullable List<String> regions) {}
}
