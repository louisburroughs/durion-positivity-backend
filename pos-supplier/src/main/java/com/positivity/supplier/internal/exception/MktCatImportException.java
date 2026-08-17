package com.positivity.supplier.internal.exception;

import java.io.Serial;

/**
 * A MKCAT import could not proceed (CAP-324 #1230).
 *
 * <p>Raised rather than reported as an empty catalogue, because the importer diffs what it fetched
 * against what it holds: "the catalogue published nothing" and "we could not read it" would
 * otherwise reach the same conclusion, and that conclusion is that every variant has been withdrawn.
 */
public class MktCatImportException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public MktCatImportException(String message) {
        super(message);
    }

    public MktCatImportException(String message, Throwable cause) {
        super(message, cause);
    }
}
