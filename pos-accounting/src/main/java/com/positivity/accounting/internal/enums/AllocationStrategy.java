package com.positivity.accounting.internal.enums;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Strategy controlling the order in which a payment is allocated across target invoices.
 *
 * <p>Crosses the payment-application service boundary as an optional field on
 * {@link com.positivity.accounting.internal.dto.PaymentApplicationRequest}; when absent the
 * behavior is {@link #CALLER_ORDER}, which is byte-identical to the pre-strategy contract.
 *
 * @see <a href=
 *      "https://github.com/louisburroughs/durion-positivity-backend/issues/955">Issue
 *      #955</a>
 */
@Schema(
        description = "Order in which the payment is allocated across the requested invoices. "
                + "CALLER_ORDER (default when absent) applies amounts in the order supplied by the caller; "
                + "OLDEST_FIRST allocates by ascending invoice date.")
public enum AllocationStrategy {

    /**
     * Apply amounts to invoices in the exact order supplied by the caller.
     * Default when no strategy is provided; preserves pre-existing behavior.
     */
    CALLER_ORDER,

    /**
     * Apply amounts to invoices ordered by ascending invoice date (oldest invoice first).
     */
    OLDEST_FIRST
}
