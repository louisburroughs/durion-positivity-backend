package com.positivity.supplier.internal.vendor;

import java.util.Locale;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The shapes a vendor tax registration's {@code scheme} and {@code region} must have whenever the entry carries a
 * number (ADR-0072 Decision 2; Security confirmation on louisburroughs/durion#571; #2621). Neither may hold a
 * digit, so an attribute stored and published beside {@code last4} can never carry part of a number.
 *
 * <p>Internal (ADR-0026): one definition for the request model and the {@code V4} migration. {@code V5} repeats
 * the same two expressions in SQL, because a SQL migration cannot call Java; its comment names this class.
 */
public final class VendorTaxRegistrationShapes {

    /** Letters, spaces, {@code _}, {@code /} and {@code -}, at most 16, starting with a letter; never a digit. */
    public static final Pattern SCHEME = Pattern.compile("^[A-Z][A-Z _/-]{0,15}$");

    /** Two letters, optionally {@code -} and one to three letters ({@code QC}, {@code CA-QC}); never a digit. */
    public static final Pattern REGION = Pattern.compile("^[A-Z]{2}(-[A-Z]{1,3})?$");

    private VendorTaxRegistrationShapes() {}

    /** Trimmed and upper-cased, as stored; {@code null} stays {@code null}. */
    public static @Nullable String normalise(@Nullable String value) {
        return value == null ? null : value.strip().toUpperCase(Locale.ROOT);
    }

    /** Whether a normalised scheme and (optional) region conform. */
    public static boolean conforms(@Nullable String scheme, @Nullable String region) {
        return scheme != null
                && SCHEME.matcher(scheme).matches()
                && (region == null || REGION.matcher(region).matches());
    }
}
