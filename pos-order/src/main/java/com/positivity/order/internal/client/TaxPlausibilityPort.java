package com.positivity.order.internal.client;

import com.positivity.shared.error.ApiError;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * pos-tax's stated-tax plausibility check and evidence-rules read, as the drawer uses them (CAP:550 S32d items 5 and
 * 6; S32b {@code POST /v1/tax/plausibility-checks} and {@code GET /v1/tax/evidence-rules}). pos-tax is a utility
 * (ADR-0044 R2) reached by a direct call with explicit timeouts.
 */
public interface TaxPlausibilityPort {

    /**
     * Asks pos-tax whether the stated tax is plausible. Never throws for pos-tax's answer or its absence: every
     * outcome is a {@link PlausibilityAnswer}.
     *
     * @param query the receipt
     * @return pos-tax's answer, classified by its {@code code}
     */
    @NonNull
    PlausibilityAnswer check(@NonNull PlausibilityQuery query);

    /**
     * The evidence rule that asks for the supplier's number on a drawer receipt in {@code countryCode} on {@code asOf}.
     *
     * @param countryCode ISO 3166-1 alpha-2 country
     * @param asOf        the business date
     * @return the rule's threshold, or empty when the country has none or pos-tax did not answer
     */
    @NonNull
    Optional<EvidenceThreshold> drawerEvidenceRule(@NonNull String countryCode, @NonNull LocalDate asOf);

    /**
     * One receipt to check. {@link #toString()} never prints the supplier's number.
     *
     * @param countryCode                the session location's country
     * @param regionCode                 its region (subdivision)
     * @param postalCode                 its postal code
     * @param city                       its city, when known
     * @param asOf                       the movement's date
     * @param currencyCode               the drawer's currency
     * @param receiptTotal               the movement's amount, tax included
     * @param statedTaxes                the stated amounts, one per regime
     * @param supplierRegistrationNumber the normalised number, when the register sent one
     */
    record PlausibilityQuery(
            @Nullable String countryCode,
            @Nullable String regionCode,
            @Nullable String postalCode,
            @Nullable String city,
            @NonNull LocalDate asOf,
            @NonNull String currencyCode,
            @NonNull BigDecimal receiptTotal,
            @NonNull List<StatedAmount> statedTaxes,
            @Nullable String supplierRegistrationNumber) {

        @Override
        public String toString() {
            return "PlausibilityQuery[countryCode=" + countryCode + ", regionCode=" + regionCode + ", asOf=" + asOf
                    + ", currencyCode=" + currencyCode + ", receiptTotal=" + receiptTotal + ", statedTaxes="
                    + statedTaxes + ", supplierRegistrationNumberProvided=" + (supplierRegistrationNumber != null)
                    + "]";
        }
    }

    /**
     * One stated amount.
     *
     * @param regime the regime code
     * @param amount the amount
     */
    record StatedAmount(@NonNull String regime, @NonNull BigDecimal amount) {}

    /**
     * The drawer receipt's evidence threshold.
     *
     * @param threshold    the receipt total, tax included, from which the supplier's number is asked for
     * @param currencyCode its ISO 4217 code
     */
    record EvidenceThreshold(
            @NonNull BigDecimal threshold, @NonNull String currencyCode) {}

    /** pos-tax's answer to a plausibility check, classified on its {@code code}, never on its status alone. */
    sealed interface PlausibilityAnswer {}

    /**
     * A 200.
     *
     * @param outcome                      {@code PLAUSIBLE} or {@code RATE_UNAVAILABLE}
     * @param supplierRegistrationRequired whether an evidence rule asks for the supplier's number
     * @param wellFormed                   whether the number matches the supplier regime's shape; null when no
     *                                     number was sent or the country names no supplier regime
     */
    record Checked(
            @NonNull String outcome,
            boolean supplierRegistrationRequired,
            @Nullable Boolean wellFormed) implements PlausibilityAnswer {}

    /**
     * A 422 with code {@code TAX_AMOUNT_IMPLAUSIBLE}: relayed to the register.
     *
     * @param message     pos-tax's message
     * @param fieldErrors pos-tax's field errors, each naming a maximum
     */
    record Implausible(@NonNull String message, @NonNull List<ApiError.FieldError> fieldErrors)
            implements PlausibilityAnswer {}

    /**
     * Any other 4xx: pos-order's replicas and pos-tax disagree.
     *
     * @param status pos-tax's status
     * @param code   pos-tax's code, when its body carried one
     */
    record Disagreement(int status, @Nullable String code) implements PlausibilityAnswer {}

    /** Unreachable, a timeout, a 5xx or an unreadable answer. */
    record Unavailable() implements PlausibilityAnswer {}
}
