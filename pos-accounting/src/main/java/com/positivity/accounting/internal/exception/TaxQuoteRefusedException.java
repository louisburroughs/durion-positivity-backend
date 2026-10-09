package com.positivity.accounting.internal.exception;

import java.util.Set;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpStatus;

/**
 * pos-tax refused a vendor bill's self-assessed (use) tax quote with a 422 from a closed list (CAP:550 S43; #2604 ruling
 * 4 as amended in comment 6076230360): {@code TAX_JURISDICTION_NOT_CONFIGURED}, {@code CURRENCY_NOT_SUPPORTED} or
 * {@code TAX_CAPABILITY_UNSUPPORTED}. pos-accounting answers 422 with the same code (ADR-0017 §2 question 3: a missing
 * configuration of a referenced resource, like {@code GL_MAPPING_NOT_CONFIGURED}; retrying cannot fix it, so never 503),
 * the {@code TaxRegistrationRelayException} precedent of one code per condition across accounting's entry points.
 *
 * <p>Only the status and the code are relayed. The message is accounting's own, naming the bill and the setting to
 * check; pos-tax's message and body are never echoed or logged. Nothing is written: no posting and no refusal row.
 */
public class TaxQuoteRefusedException extends RuntimeException {

    /** The status of every relayed refusal. */
    public static final HttpStatus STATUS = HttpStatus.UNPROCESSABLE_CONTENT;

    public static final String JURISDICTION_NOT_CONFIGURED = "TAX_JURISDICTION_NOT_CONFIGURED";
    public static final String CURRENCY_NOT_SUPPORTED = "CURRENCY_NOT_SUPPORTED";
    public static final String CAPABILITY_UNSUPPORTED = "TAX_CAPABILITY_UNSUPPORTED";

    /** The closed list of pos-tax 422 codes relayed; any other 422 is 503. */
    public static final Set<String> RELAYED_CODES =
            Set.of(JURISDICTION_NOT_CONFIGURED, CURRENCY_NOT_SUPPORTED, CAPABILITY_UNSUPPORTED);

    private final String code;

    /**
     * @param code one of {@link #RELAYED_CODES}
     * @param message accounting's own message, never pos-tax's
     */
    public TaxQuoteRefusedException(@NonNull String code, @NonNull String message) {
        super(message);
        if (!RELAYED_CODES.contains(code)) {
            throw new IllegalArgumentException("Not a relayed pos-tax code: " + code);
        }
        this.code = code;
    }

    /** The relayed pos-tax code. */
    public @NonNull String getCode() {
        return code;
    }
}
