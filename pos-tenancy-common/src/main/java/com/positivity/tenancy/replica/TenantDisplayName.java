package com.positivity.tenancy.replica;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;
import org.jspecify.annotations.NonNull;

/**
 * The one normalization of a tenant's display name (ADR-0062 §7).
 *
 * <p>A display name is what a user picks their organization by at login, so it is unique across the
 * registry case- and whitespace-insensitively. {@link #normalize} produces the key that uniqueness
 * is enforced on and that the login search matches against.
 *
 * <p>It lives here, in the library both sides already depend on, rather than once in {@code
 * pos-tenant} (which owns the registry and writes the key) and again in every module's {@code
 * ext_tenant} replica consumer (which stores it for searching). Two implementations that drifted
 * would make a tenant silently unfindable at login — no error, just no match — so there is one.
 */
public final class TenantDisplayName {

    /** Matches the {@code display_name} / {@code display_name_key} column length. */
    public static final int MAX_LENGTH = 200;

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private TenantDisplayName() {
        // Utility class
    }

    /**
     * The normalized key for a display name: NFKC, internal whitespace collapsed to one space,
     * trimmed, case-folded, and bounded to {@link #MAX_LENGTH}. {@code "Acme  Tire & Auto "} and
     * {@code "acme tire & auto"} both yield {@code "acme tire & auto"}.
     *
     * <p>The bound is not belt-and-braces. Both transformations can <em>lengthen</em> a string that
     * already fits: NFKC expands compatibility forms (one {@code U+FB03} ligature becomes three
     * characters, so 200 of them become 600), and case folding expands others ({@code U+0130}
     * lowercases to two characters, so 200 become 400). Unbounded, either would overflow the
     * {@code varchar(200)} column and fail the write.
     */
    public static @NonNull String normalize(@NonNull String displayName) {
        return truncate(collapse(displayName).toLowerCase(Locale.ROOT));
    }

    /**
     * The display form as it is stored: NFKC, internal whitespace collapsed, trimmed, truncated to
     * {@link #MAX_LENGTH}. The operator's casing is theirs and is left alone.
     */
    public static @NonNull String displayForm(@NonNull String displayName) {
        return truncate(collapse(displayName));
    }

    private static String collapse(String value) {
        String nfkc = Normalizer.normalize(value, Normalizer.Form.NFKC);
        return WHITESPACE.matcher(nfkc).replaceAll(" ").strip();
    }

    /**
     * Cuts to {@link #MAX_LENGTH} without splitting a surrogate pair — a blind {@code substring}
     * would leave a lone high surrogate, which is not valid text and which Postgres rejects.
     */
    private static String truncate(String value) {
        if (value.length() <= MAX_LENGTH) {
            return value;
        }
        int end = MAX_LENGTH;
        if (Character.isHighSurrogate(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(0, end).strip();
    }
}
