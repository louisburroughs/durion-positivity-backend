package com.positivity.inventory.internal.exception;

/**
 * Thrown when a receiving session names a purchase order that the {@code ext_purchase_order}
 * projection does not (yet) hold (issue #1492).
 *
 * <p>{@code PurchaseOrderUpdatedV1} projects a header and its lines atomically, and ADR-0044 rules
 * out a synchronous call to pos-order to tell replication lag apart from an unknown purchase order
 * id — this module can only see the missing row, not the reason for it. Maps to a deterministic
 * 409 with code {@code SOURCE_DOCUMENT_LINES_UNAVAILABLE} and a guided {@code nextAction}: retry
 * shortly, since the projection may still be catching up, and treat the id as unknown if it does
 * not.
 */
public class SourceDocumentLinesUnavailableException extends RuntimeException {

    public static final String ERROR_CODE = "SOURCE_DOCUMENT_LINES_UNAVAILABLE";

    public SourceDocumentLinesUnavailableException(String message) {
        super(message);
    }
}
