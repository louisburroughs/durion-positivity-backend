package com.positivity.tax.internal.service;

import com.positivity.tax.internal.dto.PlausibilityCheckRequest;
import com.positivity.tax.internal.dto.PlausibilityCheckResponse;
import org.jspecify.annotations.NonNull;

/**
 * The stated-tax plausibility stub (CAP:550 S32b, AW55): a bookkeeping control against typing errors on a
 * receipt, not a tax rule.
 * <p>
 * A stated amount is implausible ({@code 422 TAX_AMOUNT_IMPLAUSIBLE}) when it, or the sum of all of them,
 * reaches the receipt total {@code T}, or when it is above {@code T × r / (1 + r)} rounded up to the minor unit
 * plus {@code pos.tax.plausibility.tolerance-minor-units}. {@code r} is the rate of the one row of the regime's
 * tax types in effect for the region on the date, and {@code 0} for a regime the region's rows do not levy.
 * There is no combined bound. A region with no row on the date answers {@link #RATE_UNAVAILABLE} and only the
 * total check applies. The check is pure: it reads no tenant data, changes no state and emits no event.
 */
public interface TaxPlausibilityService {

    /** Outcome: every stated amount is within its bound. */
    String PLAUSIBLE = "PLAUSIBLE";

    /** Outcome: the region has no rate row on the date, so only the total check applied. */
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
