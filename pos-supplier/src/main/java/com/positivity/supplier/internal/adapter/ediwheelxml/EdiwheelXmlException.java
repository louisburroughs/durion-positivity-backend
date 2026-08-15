package com.positivity.supplier.internal.adapter.ediwheelxml;

/**
 * An EDIWheel document could not be serialised or parsed.
 *
 * <p>Deliberately family-agnostic and deliberately not surfaced beyond an adapter: each family
 * translates it into its own typed encode/decode failure, because what a broken document
 * <em>means</em> differs by capability. An unreadable order response is a transmission ambiguity
 * (ADR-0052 §3); an unreadable inquiry response is just an unavailable vendor.
 */
public class EdiwheelXmlException extends RuntimeException {

    public EdiwheelXmlException(String message) {
        super(message);
    }

    public EdiwheelXmlException(String message, Throwable cause) {
        super(message, cause);
    }
}
