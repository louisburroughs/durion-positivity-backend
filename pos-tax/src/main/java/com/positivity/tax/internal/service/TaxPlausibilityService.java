package com.positivity.tax.internal.service;

import com.positivity.tax.internal.dto.PlausibilityCheckRequest;
import com.positivity.tax.internal.dto.PlausibilityCheckResponse;
import org.jspecify.annotations.NonNull;

/**
 * The stated-tax plausibility stub (CAP:550 S32b, AW55): a bookkeeping control against typing errors on a
 * receipt, not a tax rule.
 * <p>
 * Refusals, in order; the first failing step answers with all of its own field errors: 400
 * {@code VALIDATION_ERROR} (shape, including a repeated regime), 422 {@code TAX_JURISDICTION_NOT_CONFIGURED},
 * 422 {@code CURRENCY_NOT_SUPPORTED}, 422 {@code AMOUNT_PRECISION_EXCEEDS_CURRENCY}, 422
 * {@code TAX_REGIME_NOT_DECLARED}, then 422 {@code TAX_AMOUNT_IMPLAUSIBLE}.
 * <p>
 * A stated amount is implausible when it, or the sum of all of them, reaches the receipt total {@code T}, or
 * when it is above {@code T × r / (1 + r)} rounded up to the minor unit plus
 * {@code pos.tax.plausibility.tolerance-minor-units}. {@code r} is decided per regime: <em>rated</em> when a row
 * of the regime's tax types is in effect for the region on the date (that row's rate); <em>not levied</em>
 * ({@code r = 0}) when there is no row and the regime does not cover the region (its configured regions are
 * neither empty nor contain it); <em>unrated</em> when there is no row but the regime covers the region, so no
 * rate bound applies and the regime appears in neither {@code ratesUsed} nor {@code maximums}. The total check
 * always applies. There is no combined bound. The outcome is {@link #RATE_UNAVAILABLE} when at least one stated
 * amount above zero is unrated, otherwise {@link #PLAUSIBLE}. The check is pure: it reads no tenant data,
 * changes no state and emits no event.
 */
public interface TaxPlausibilityService {

    /** Outcome: every stated amount is within its bound, or there is none above zero. */
    String PLAUSIBLE = "PLAUSIBLE";

    /** Outcome: at least one stated amount above zero is unrated, so only the total check bounded it. */
    String RATE_UNAVAILABLE = "RATE_UNAVAILABLE";

    /** The refusal code, also the counter tag of a refused check. */
    String TAX_AMOUNT_IMPLAUSIBLE = "TAX_AMOUNT_IMPLAUSIBLE";

    /** Name of the Micrometer counter, tagged {@code outcome} only. */
    String OUTCOME_COUNTER = "pos.tax.plausibility.outcome";

    /**
     * Checks the tax stated on a receipt.
     *
     * @param request the receipt, already bean-validated
     * @return the outcome, the rates and maximums used, and the supplier-number answers
     * @throws com.positivity.tax.internal.exception.TaxRequestInvalidException       a regime stated twice (400)
     * @throws com.positivity.tax.internal.exception.TaxRequestUnprocessableException the configuration or
     *     currency refuses the request (422)
     * @throws com.positivity.tax.internal.exception.TaxAmountImplausibleException    a stated amount is
     *     implausible (422)
     */
    @NonNull
    PlausibilityCheckResponse check(@NonNull PlausibilityCheckRequest request);
}
