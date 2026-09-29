package com.positivity.accounting.internal.bankrec.service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * The SHA-256 of a command's canonical form, which tells an idempotent replay from a reuse of its
 * {@code requestId} with another payload (story S4, #2303). Each field is length-prefixed ({@code len:text}) and an
 * absent one is {@code ~}, so a delimiter inside free text cannot collide with a shifted field boundary and an
 * absent field differs from the literal text {@code null}. Amounts are compared by value, not scale.
 */
final class CanonicalRequestHash {

    private final StringBuilder canonical = new StringBuilder();

    /** Appends one field; {@code null} is the absent marker. */
    @NonNull
    CanonicalRequestHash field(@Nullable Object value) {
        if (value == null) {
            canonical.append('~');
            return this;
        }
        String text =
                value instanceof BigDecimal amount ? amount.stripTrailingZeros().toPlainString() : value.toString();
        canonical.append(text.length()).append(':').append(text);
        return this;
    }

    /** The lowercase hex SHA-256 of the fields appended so far. */
    @NonNull
    String digest() {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256")
                            .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java platform", e);
        }
    }
}
