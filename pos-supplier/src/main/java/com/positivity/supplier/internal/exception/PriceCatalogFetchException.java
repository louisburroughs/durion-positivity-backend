package com.positivity.supplier.internal.exception;

import com.positivity.supplier.internal.enums.PriceCatalogErrorCode;
import java.io.Serial;
import org.jspecify.annotations.NonNull;

/**
 * A vendor price catalogue could not be read (CAP-318, #1349).
 *
 * <p>Thrown rather than returned as an empty catalogue. An empty PRICAT is a statement — "this
 * vendor sells nothing" — and the import that acted on one would expire every vendor price it holds
 * because a request timed out.
 *
 * <p>Carries the {@link PriceCatalogErrorCode} category alongside the message (#1637 decision 5),
 * decided at the throw site — the only place that knows whether the exchange failed or the
 * document would not decode — so the import row that records the failure can carry a structured
 * code as well as the operator text.
 */
public class PriceCatalogFetchException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final PriceCatalogErrorCode errorCode;

    public PriceCatalogFetchException(@NonNull PriceCatalogErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public PriceCatalogFetchException(@NonNull PriceCatalogErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    /** The stable failure category, recorded on the FAILED import row next to the free text. */
    @NonNull
    public PriceCatalogErrorCode getErrorCode() {
        return errorCode;
    }
}
