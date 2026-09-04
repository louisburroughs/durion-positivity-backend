package com.positivity.accounting.internal.exception;

/**
 * Thrown when an operator action against a vendor bill's match/exception
 * workflow cannot proceed: the bill or match candidate does not exist, the
 * bill is not in MATCH_EXCEPTION status, the candidate is already resolved,
 * or the requested resolution action is not one of ACCEPT/VOID/CORRECT.
 * Maps to HTTP 400 (VALIDATION_ERROR) — deliberately, not 404, matching the
 * documented contract on {@code resolveVendorBillMatchException} and
 * {@code selectVendorBillMatchCandidate} (the latter's Javadoc states this
 * explicitly: "mapped as VALIDATION_ERROR, not 404").
 */
public class VendorBillOperatorActionException extends RuntimeException {

    public VendorBillOperatorActionException(String message) {
        super(message);
    }
}
