package com.positivity.inventory.internal.exception;

import java.util.UUID;

/**
 * A purchase-suggestion convert request violated a conversion precondition (odoo-parity
 * F4, issue #1044): deterministic 422 with a per-case error code, mirroring the module's
 * other guided-422 exceptions.
 */
public class PurchaseSuggestionConversionException extends RuntimeException {
    private static final String SUGGESTION_PREFIX = "Suggestion ";

    private final String errorCode;

    private PurchaseSuggestionConversionException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }

    /** A listed suggestion is not in ACCEPTED status (D-3: human accept precedes conversion). */
    public static PurchaseSuggestionConversionException notAccepted(UUID suggestionId, String status) {
        return new PurchaseSuggestionConversionException(
                "PURCHASE_SUGGESTION_NOT_ACCEPTED",
                SUGGESTION_PREFIX + suggestionId + " is " + status + "; only ACCEPTED suggestions can be converted");
    }

    /** The listed suggestions do not all share one vendor (one convert = one single-vendor PO). */
    public static PurchaseSuggestionConversionException vendorMismatch() {
        return new PurchaseSuggestionConversionException(
                "PURCHASE_SUGGESTION_VENDOR_MISMATCH",
                "All suggestions in one convert request must share a single vendor; convert mixed vendors in"
                        + " separate requests");
    }

    /**
     * The listed suggestions are priced in different currencies. A purchase order has one
     * currency and its lines none of their own, so they cannot share an order (ADR-0067 DF-5).
     */
    public static PurchaseSuggestionConversionException currencyMismatch() {
        return new PurchaseSuggestionConversionException(
                "PURCHASE_SUGGESTION_CURRENCY_MISMATCH",
                "All suggestions in one convert request must be priced in a single currency; convert each currency"
                        + " in a separate request");
    }

    /** A listed suggestion has a unit cost but no currency; it is never assumed to be in any one. */
    public static PurchaseSuggestionConversionException missingCurrency(UUID suggestionId) {
        return new PurchaseSuggestionConversionException(
                "PURCHASE_SUGGESTION_MISSING_CURRENCY",
                SUGGESTION_PREFIX + suggestionId + " carries a unit cost with no currency; create the purchase order"
                        + " manually");
    }

    /** A listed suggestion has no selectable vendor and cannot be placed on a purchase order. */
    public static PurchaseSuggestionConversionException missingVendor(UUID suggestionId) {
        return new PurchaseSuggestionConversionException(
                "PURCHASE_SUGGESTION_MISSING_VENDOR",
                SUGGESTION_PREFIX + suggestionId + " has no selected vendor (no usable vendor feed data); create the"
                        + " purchase order manually");
    }

    /** A listed suggestion has no feed price; a PO line requires a positive unit cost. */
    public static PurchaseSuggestionConversionException missingUnitCost(UUID suggestionId) {
        return new PurchaseSuggestionConversionException(
                "PURCHASE_SUGGESTION_MISSING_UNIT_COST",
                SUGGESTION_PREFIX + suggestionId + " carries no vendor feed price; purchase order lines require a"
                        + " unit cost — create the purchase order manually");
    }

    /** The listed suggestions resolve to different ship-to sites; a PO ships to one site. */
    public static PurchaseSuggestionConversionException siteMismatch() {
        return new PurchaseSuggestionConversionException(
                "PURCHASE_SUGGESTION_SITE_MISMATCH",
                "All suggestions in one convert request must resolve to a single ship-to site; convert per site");
    }
}
