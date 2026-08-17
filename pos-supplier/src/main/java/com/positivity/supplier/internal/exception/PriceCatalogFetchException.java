package com.positivity.supplier.internal.exception;

import java.io.Serial;

/**
 * A vendor price catalogue could not be read (CAP-318, #1349).
 *
 * <p>Thrown rather than returned as an empty catalogue. An empty PRICAT is a statement — "this
 * vendor sells nothing" — and the import that acted on one would expire every vendor price it holds
 * because a request timed out.
 */
public class PriceCatalogFetchException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public PriceCatalogFetchException(String message) {
        super(message);
    }

    public PriceCatalogFetchException(String message, Throwable cause) {
        super(message, cause);
    }
}
