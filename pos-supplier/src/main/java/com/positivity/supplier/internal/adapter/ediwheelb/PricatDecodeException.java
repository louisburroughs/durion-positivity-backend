package com.positivity.supplier.internal.adapter.ediwheelb;

/**
 * The vendor answered, but with something that is not a usable PRICAT B4.0 document — a body that
 * does not parse, or one carrying a document-level vendor error code.
 *
 * <p>Distinct from a rejected line: a rejected line is quarantined and the import continues, while
 * this fails the import. That distinction is deliberate — treating an unreadable document as an
 * empty catalog would let a vendor-side outage look like "this supplier has no prices".
 */
public class PricatDecodeException extends RuntimeException {

    public PricatDecodeException(String message) {
        super(message);
    }

    public PricatDecodeException(String message, Throwable cause) {
        super(message, cause);
    }
}
