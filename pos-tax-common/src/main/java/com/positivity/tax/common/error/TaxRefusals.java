package com.positivity.tax.common.error;

import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * pos-tax's 422 refusal codes a caller must answer deterministically (#2644, ADR-0017), and the one way to read the
 * code out of pos-tax's {@code ApiError} body, so the three callers (pos-invoice, pos-order, pos-workorder) share
 * both instead of carrying their own literals and parsers.
 */
public final class TaxRefusals {

    /** The request's currency is not the destination country profile's (ADR-0067 PC-9). */
    public static final String CURRENCY_NOT_SUPPORTED = "CURRENCY_NOT_SUPPORTED";

    /** No rates are configured for the destination jurisdiction. */
    public static final String TAX_JURISDICTION_NOT_CONFIGURED = "TAX_JURISDICTION_NOT_CONFIGURED";

    private static final Pattern ERROR_CODE = Pattern.compile("\"code\"\\s*:\\s*\"([A-Z0-9_]+)\"");

    private TaxRefusals() {}

    /** The {@code code} of an {@code ApiError} body, or null when the body carries none. */
    public static @Nullable String codeOf(byte @Nullable [] body) {
        if (body == null || body.length == 0) {
            return null;
        }
        Matcher matcher = ERROR_CODE.matcher(new String(body, StandardCharsets.UTF_8));
        return matcher.find() ? matcher.group(1) : null;
    }
}
