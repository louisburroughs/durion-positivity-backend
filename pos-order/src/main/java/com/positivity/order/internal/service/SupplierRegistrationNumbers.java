package com.positivity.order.internal.service;

import java.util.Optional;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * pos-order's copy of pos-tax's normalisation of a supplier's registration number (CAP:550 S32d item 7), plus the
 * drawer's local shape check.
 *
 * <p>pos-order never imports pos-tax (ADR-0044), so the rule is re-implemented here, exactly as pos-tax's {@code
 * RegistrationNumberShapes#normalize} applies it, in this order:
 *
 * <ol>
 *   <li>{@link String#trim()} semantics: characters at or below U+0020 are removed at either end. It is not {@code
 *       strip()}, so other Unicode whitespace stays.
 *   <li>Every U+0020 SPACE and U+002D HYPHEN-MINUS is removed, wherever it occurs, and nothing else.
 *   <li>ASCII {@code a}-{@code z} only are upper-cased; nothing is locale-dependent.
 * </ol>
 *
 * <p>The same normalisation cases run in pos-order's {@code SupplierRegistrationNumbersTest} and in pos-tax's {@code
 * RegistrationNumberShapesTest} (AC 25), with identical inputs and outputs, so the two copies cannot drift. A change to
 * one table without the other is a review failure.
 *
 * <p>The number is INTERNAL (ADR-0072 Decision 1): nothing here logs it or puts it in a message.
 */
public final class SupplierRegistrationNumbers {

    /** The longest number the drawer accepts after normalising. */
    public static final int MAX_LENGTH = 32;

    private SupplierRegistrationNumbers() {}

    /**
     * The number normalised as pos-tax normalises it.
     *
     * @param number the number as the register sent it
     * @return the normalised number, possibly empty
     */
    public static @NonNull String normalize(@NonNull String number) {
        String trimmed = number.trim();
        StringBuilder normalized = new StringBuilder(trimmed.length());
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c == ' ' || c == '-') {
                continue;
            }
            normalized.append(c >= 'a' && c <= 'z' ? (char) (c - ('a' - 'A')) : c);
        }
        return normalized.toString();
    }

    /**
     * The normalised number when it passes the drawer's local check: 1 to {@value #MAX_LENGTH} characters, none of
     * them whitespace, a space character or an ISO control. Empty otherwise, so the register gets a 400 without
     * pos-tax being asked. Any other character (an en dash, a Cyrillic letter) passes here and is left to pos-tax's
     * shape check.
     *
     * @param number the number as the register sent it
     * @return the normalised number, or empty when it fails the local check
     */
    public static @NonNull Optional<String> locallyAccepted(@Nullable String number) {
        if (number == null) {
            return Optional.empty();
        }
        String normalized = normalize(number);
        if (normalized.isEmpty() || normalized.length() > MAX_LENGTH) {
            return Optional.empty();
        }
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            if (Character.isWhitespace(c) || Character.isSpaceChar(c) || Character.isISOControl(c)) {
                return Optional.empty();
            }
        }
        return Optional.of(normalized);
    }
}
