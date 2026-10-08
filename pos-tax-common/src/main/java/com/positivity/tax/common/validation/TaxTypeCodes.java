package com.positivity.tax.common.validation;

import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The shape of a tax-type code (CAP:550 S32a, louisburroughs/durion-positivity-backend#2636).
 * <p>
 * There is no tax-type enum. The vocabulary is configuration only: each country profile in pos-tax
 * ({@code pos.tax.countries.<country>.tax-types}) declares its own codes, and pos-tax refuses an
 * undeclared code at startup. Code never names a tax type, so a new country needs no code change
 * (platform owner direction, 2026-10-08: multi-national readiness).
 * <p>
 * A code is 1–32 upper-case letters, digits or underscores, which fits the {@code varchar(32)}
 * columns downstream. A reader copies a well-formed code exactly as received and never infers one;
 * a malformed value reads as {@code null} (untyped).
 */
public final class TaxTypeCodes {

    /** Regular expression every tax-type code matches. */
    public static final String REGEX = "^[A-Z0-9_]{1,32}$";

    private static final Pattern PATTERN = Pattern.compile(REGEX);

    private TaxTypeCodes() {}

    /**
     * Whether {@code code} is a well-formed tax-type code.
     *
     * @param code the candidate; may be {@code null}
     * @return {@code true} when it matches {@link #REGEX}
     */
    public static boolean isWellFormed(@Nullable String code) {
        return code != null && PATTERN.matcher(code).matches();
    }

    /**
     * The code as received when it is well formed, otherwise {@code null}. Never normalizes or
     * infers.
     *
     * @param code the received value; may be {@code null}
     * @return the same code, or {@code null}
     */
    @Nullable
    public static String wellFormedOrNull(@Nullable String code) {
        return isWellFormed(code) ? code : null;
    }
}
