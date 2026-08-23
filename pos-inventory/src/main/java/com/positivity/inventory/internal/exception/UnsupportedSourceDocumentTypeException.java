package com.positivity.inventory.internal.exception;

/**
 * A receiving session was requested against a source document type receiving does not support
 * (issue #1480).
 *
 * <p>Today that means anything other than {@code PO}. ASN was never supported in fact: the retired
 * stub client resolved it to a {@code pos-shipments} service that does not exist, so it could only
 * produce a {@code 404} that read as "no such document". Saying so plainly is the honest answer —
 * a {@code 422}, because the request is well-formed and the type is simply not one receiving
 * knows how to resolve.
 */
public class UnsupportedSourceDocumentTypeException extends RuntimeException {

    public static final String ERROR_CODE = "UNSUPPORTED_SOURCE_DOCUMENT_TYPE";

    public UnsupportedSourceDocumentTypeException(String sourceDocumentType) {
        super("Receiving sessions are supported for purchase orders only; source document type " + sourceDocumentType
                + " has no owning service to resolve its lines");
    }
}
