package com.positivity.supplier.internal.service;

import java.util.Objects;
import java.util.regex.Pattern;
import org.jspecify.annotations.NonNull;

/**
 * A parsed scheme-prefixed secret reference (ADR-0050 §4): {@code scheme:key}, e.g.
 * {@code env:MICHELIN_EDI_PASSWORD}. References are configuration data — the referenced
 * secret value is resolved at call time only ({@link SecretReferenceResolver}) and never
 * stored, serialized, or logged.
 *
 * @param scheme the resolution scheme (e.g. {@code env}); never blank
 * @param key the scheme-local key; never blank
 */
public record SecretReference(
        @NonNull String scheme, @NonNull String key) {

    private static final Pattern SCHEME_PATTERN = Pattern.compile("[a-zA-Z][a-zA-Z0-9+.-]*");

    public SecretReference {
        Objects.requireNonNull(scheme, "scheme must not be null");
        Objects.requireNonNull(key, "key must not be null");
        if (!SCHEME_PATTERN.matcher(scheme).matches()) {
            throw new IllegalArgumentException("secret reference scheme is malformed");
        }
        if (key.isBlank()) {
            throw new IllegalArgumentException("secret reference key must not be blank");
        }
    }

    /**
     * Parses a raw {@code scheme:key} reference.
     *
     * @param raw the raw reference text
     * @return the parsed reference
     * @throws IllegalArgumentException when the text is not of {@code scheme:key} shape —
     *     including plaintext-looking values with no scheme prefix
     */
    @NonNull
    public static SecretReference parse(@NonNull String raw) {
        Objects.requireNonNull(raw, "secret reference must not be null");
        int separator = raw.indexOf(':');
        if (separator <= 0 || separator == raw.length() - 1) {
            throw new IllegalArgumentException(
                    "secret reference must be of 'scheme:key' shape (e.g. env:VAR_NAME), never a plaintext value");
        }
        return new SecretReference(raw.substring(0, separator), raw.substring(separator + 1));
    }

    /** Whether the raw text parses as a well-formed {@code scheme:key} reference. */
    public static boolean isWellFormed(@NonNull String raw) {
        try {
            parse(raw);
            return true;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }
}
