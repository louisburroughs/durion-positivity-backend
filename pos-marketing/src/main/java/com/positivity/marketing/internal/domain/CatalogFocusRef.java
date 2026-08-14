package com.positivity.marketing.internal.domain;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;
import org.jspecify.annotations.NonNull;

/**
 * The catalog thing a campaign points at, written {@code kind:value} (Story #1148).
 *
 * <p>A campaign stores this as free text because the catalog aggregate lives in another
 * domain and cannot be referenced by key. Free text with no grammar, though, is a reference
 * nobody can ever follow: {@code "alignment"} and {@code "Service - Alignment"} both look
 * plausible to the marketer typing them and mean nothing to any consumer. Pinning the shape
 * here is what makes the value interpretable at all, and it is the check that runs before the
 * campaign is allowed to go out.
 *
 * <p>Recognized kinds:
 *
 * <ul>
 *   <li>{@code product:<id-or-name>} — a catalog product
 *   <li>{@code sku:<sku>} — a product by stock-keeping unit
 *   <li>{@code service:<id-or-name>} — a catalog service, e.g. {@code service:alignment}
 *   <li>{@code category:<id-or-name>} — a whole product category
 * </ul>
 *
 * @param kind recognized reference kind, lower-cased
 * @param value the identifier or name after the separator, trimmed
 */
public record CatalogFocusRef(@NonNull Kind kind, @NonNull String value) {

    private static final char SEPARATOR = ':';

    /** Catalog reference kinds a campaign may point at. */
    public enum Kind {
        PRODUCT,
        SKU,
        SERVICE,
        CATEGORY;

        static Optional<Kind> of(String token) {
            return Arrays.stream(values())
                    .filter(kind -> kind.name().equalsIgnoreCase(token))
                    .findFirst();
        }

        /** Wire form, as written in a {@code catalogFocusRef}. */
        public String wireName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * Parse a stored reference.
     *
     * @return the parsed reference, or empty when {@code raw} does not follow the grammar —
     *     unparseable is reported rather than thrown, because the caller gathers every campaign
     *     readiness problem at once instead of failing on the first
     */
    public static @NonNull Optional<CatalogFocusRef> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String trimmed = raw.trim();
        int separator = trimmed.indexOf(SEPARATOR);
        if (separator <= 0 || separator == trimmed.length() - 1) {
            return Optional.empty();
        }
        // Split on the first separator only: a name is allowed to contain colons, a kind is not.
        String value = trimmed.substring(separator + 1).trim();
        if (value.isEmpty()) {
            return Optional.empty();
        }
        return Kind.of(trimmed.substring(0, separator).trim()).map(kind -> new CatalogFocusRef(kind, value));
    }

    /** The recognized kinds, for an error message that tells the marketer what to write instead. */
    public static @NonNull String supportedKinds() {
        return Arrays.stream(Kind.values()).map(Kind::wireName).collect(Collectors.joining(", "));
    }

    @Override
    public @NonNull String toString() {
        return kind.wireName() + SEPARATOR + value;
    }
}
