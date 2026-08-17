package com.positivity.supplier.internal.adapter.ediwheelc12;

import java.io.Serial;

/**
 * A C1.2 MKCAT response could not be read (CAP-324 #1230).
 *
 * <p>Raised rather than returned as an empty catalogue. An empty result and an unreadable one lead
 * to opposite conclusions: the first means the vendor published nothing, the second means we cannot
 * tell — and a diffing importer that treated the second as the first would conclude every variant it
 * previously held had been withdrawn.
 */
public class MktCatDecodeException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public MktCatDecodeException(String message) {
        super(message);
    }

    public MktCatDecodeException(String message, Throwable cause) {
        super(message, cause);
    }
}
