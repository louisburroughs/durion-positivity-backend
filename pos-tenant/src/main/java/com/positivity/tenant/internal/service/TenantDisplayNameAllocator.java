package com.positivity.tenant.internal.service;

import com.positivity.tenant.internal.exception.DuplicateResourceException;
import java.text.Normalizer;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.jspecify.annotations.NonNull;

/**
 * Normalizes tenant display names and seeds one for a tenant registered without a name.
 *
 * <p>A display name is what a user picks their organization by at login, so two tenants may not
 * hold names that differ only in case or spacing. {@link #normalize} produces the key the unique
 * constraint is built on: NFKC, trimmed, internal whitespace collapsed to one space, case-folded.
 * The application writes that key on every tenant write — see
 * {@code V3__tenant_display_name_key.sql} for why it is not a database-generated column.
 *
 * <p>{@link #allocate} is the fallback for a registration that supplies no name: the owning
 * account's legal name, which is required and already unique across the registry, suffixed
 * {@code  #2}, {@code  #3} … only if that would collide. An account's first tenant therefore
 * carries the bare legal name and a number appears only once there is something to tell apart. The
 * seed is a fallback, not the intended outcome — a multi-tenant account usually wants something
 * meaningful ({@code Acme Tire — Tucson}), which the operator supplies explicitly.
 *
 * <p>Pure: it takes a predicate rather than a repository, so the collision rule is testable without
 * a database.
 */
public final class TenantDisplayNameAllocator {

    /** Matches the {@code display_name} / {@code display_name_key} column length. */
    static final int MAX_LENGTH = 200;

    /** Ceiling on the suffix search, so a pathological registry fails fast instead of looping. */
    static final int MAX_ATTEMPTS = 50;

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private TenantDisplayNameAllocator() {
        // Utility class
    }

    /**
     * The normalized key for a display name: NFKC, internal whitespace collapsed, trimmed,
     * case-folded. {@code "Acme  Tire & Auto "} and {@code "acme tire & auto"} both yield
     * {@code "acme tire & auto"}.
     */
    public static @NonNull String normalize(@NonNull String displayName) {
        String nfkc = Normalizer.normalize(displayName, Normalizer.Form.NFKC);
        return WHITESPACE.matcher(nfkc).replaceAll(" ").strip().toLowerCase(Locale.ROOT);
    }

    /**
     * The display form of a name as it is stored: NFKC, internal whitespace collapsed, trimmed,
     * truncated to the column length. Casing is the operator's and is left alone.
     */
    public static @NonNull String displayForm(@NonNull String displayName) {
        String nfkc = Normalizer.normalize(displayName, Normalizer.Form.NFKC);
        String collapsed = WHITESPACE.matcher(nfkc).replaceAll(" ").strip();
        return collapsed.length() <= MAX_LENGTH
                ? collapsed
                : collapsed.substring(0, MAX_LENGTH).strip();
    }

    /**
     * Seeds a display name from the owning account's legal name.
     *
     * @param legalName the owning account's legal name
     * @param keyTaken answers whether a normalized key is already held by some tenant
     * @return the display form to store; its key is free
     * @throws DuplicateResourceException when {@value #MAX_ATTEMPTS} suffixes are all taken
     */
    public static @NonNull String allocate(@NonNull String legalName, @NonNull Predicate<String> keyTaken) {
        String base = displayForm(legalName);
        if (!keyTaken.test(normalize(base))) {
            return base;
        }
        for (int n = 2; n <= MAX_ATTEMPTS; n++) {
            String candidate = withSuffix(base, " #" + n);
            if (!keyTaken.test(normalize(candidate))) {
                return candidate;
            }
        }
        throw new DuplicateResourceException("Could not seed a free tenant display name from account legal name after "
                + MAX_ATTEMPTS + " attempts: " + legalName);
    }

    /** Appends the suffix, trimming the base so the result stays inside the column length. */
    private static String withSuffix(String base, String suffix) {
        int room = MAX_LENGTH - suffix.length();
        String head = base.length() <= room ? base : base.substring(0, room).strip();
        return head + suffix;
    }
}
