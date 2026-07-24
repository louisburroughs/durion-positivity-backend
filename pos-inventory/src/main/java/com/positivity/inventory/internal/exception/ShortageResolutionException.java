package com.positivity.inventory.internal.exception;

/**
 * A shortage-resolve request could not be executed under the current constraints (odoo-parity G2,
 * issue #1049): a deterministic 422 with a per-case error code, mirroring the module's other
 * guided-422 exceptions.
 */
public class ShortageResolutionException extends RuntimeException {

    private final String errorCode;

    private ShortageResolutionException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }

    /** A required field for the chosen option was missing (e.g. substituteSku for SUBSTITUTE). */
    public static ShortageResolutionException missingField(String field, String optionType) {
        return new ShortageResolutionException(
                "SHORTAGE_RESOLVE_MISSING_FIELD",
                "Field '" + field + "' is required to resolve a shortage with option " + optionType);
    }

    /** The chosen substitute has no live availability at the shortage site. */
    public static ShortageResolutionException substituteUnavailable(String substituteSku) {
        return new ShortageResolutionException(
                "SHORTAGE_RESOLVE_SUBSTITUTE_UNAVAILABLE",
                "Substitute " + substituteSku + " has no available stock at the shortage site");
    }

    /** A SKU or site identifier was not a valid UUID where a UUID artifact reference is required. */
    public static ShortageResolutionException invalidIdentifier(String field, String value) {
        return new ShortageResolutionException(
                "SHORTAGE_RESOLVE_INVALID_IDENTIFIER",
                "Field '" + field + "' value '" + value + "' is not a valid identifier for this option");
    }
}
