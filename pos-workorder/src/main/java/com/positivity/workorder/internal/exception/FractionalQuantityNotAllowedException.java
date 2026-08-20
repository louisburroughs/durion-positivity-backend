package com.positivity.workorder.internal.exception;

import java.math.BigDecimal;

/**
 * A part quantity carries more decimal places than the product's catalog declaration allows
 * (ADR-0055, #1413).
 *
 * <p>Divisibility is a per-product property, not a platform rule: a product whose {@code BASE}
 * unit-of-measure row declares {@code precision_scale = 0} — and equally, a product with no
 * unit-of-measure rows at all — is stocked, issued and billed in whole units, while one declaring a
 * non-zero scale may be quantified to that many decimals.
 *
 * <p>Surfaced as 422 UNPROCESSABLE ENTITY rather than 400: the request is well-formed and the field
 * passes its bean-validation bounds; what it violates is a rule about the referenced product, which
 * can only be known after looking that product up.
 *
 * <p>Carries the remedy rather than only the complaint. A counter told "0.5 is invalid" has nowhere
 * to go; told "enter 1 to issue and bill the full container" they can finish the job, which is the
 * partial-container policy ADR-0055 records.
 */
public class FractionalQuantityNotAllowedException extends RuntimeException {

    /** The request field the violation belongs to; the same name on every entry point. */
    public static final String FIELD = "quantity";

    private final transient String nextAction;

    public FractionalQuantityNotAllowedException(String message, String nextAction) {
        super(message);
        this.nextAction = nextAction;
    }

    public String getNextAction() {
        return nextAction;
    }

    /**
     * The product is stocked in whole units. The suggested value rounds up, matching the
     * "bill the whole container" policy: half a container consumed is a whole container gone.
     */
    public static FractionalQuantityNotAllowedException wholeUnitsOnly(
            String partLabel, BigDecimal quantity, BigDecimal suggestion) {
        String remedy = "Enter " + suggestion.toPlainString() + " to issue and bill the full container.";
        return new FractionalQuantityNotAllowedException(
                "Quantity " + quantity.toPlainString() + " is not valid for '" + partLabel
                        + "' — this part is issued and billed in whole units. " + remedy,
                remedy);
    }

    /** The product is divisible, but not this finely. */
    public static FractionalQuantityNotAllowedException scaleExceeded(
            String partLabel, BigDecimal quantity, int declaredScale, BigDecimal suggestion) {
        String remedy = "Enter a quantity with at most " + declaredScale + " decimal places, such as "
                + suggestion.toPlainString() + ".";
        return new FractionalQuantityNotAllowedException(
                "Quantity " + quantity.toPlainString() + " is not valid for '" + partLabel
                        + "' — this part is stocked to " + declaredScale + " decimal places. " + remedy,
                remedy);
    }
}
