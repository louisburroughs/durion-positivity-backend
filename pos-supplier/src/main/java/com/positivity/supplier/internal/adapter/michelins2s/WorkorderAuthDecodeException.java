package com.positivity.supplier.internal.adapter.michelins2s;

import java.io.Serial;

/**
 * A Michelin S2S workorder response could not be read (CAP-323 #1229).
 *
 * <p>Raised rather than returned as an "unknown" status, because every caller of the decoder is
 * about to decide whether a shop may do work. An unreadable answer must not be able to flow onward
 * looking like an answer.
 */
public class WorkorderAuthDecodeException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public WorkorderAuthDecodeException(String message) {
        super(message);
    }

    public WorkorderAuthDecodeException(String message, Throwable cause) {
        super(message, cause);
    }
}
