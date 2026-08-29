package com.positivity.bulkloader.internal.domain;

import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Small checks the loader strategies share.
 *
 * <p>Every strategy reads a file of strings and has to say the same things about them: this is
 * required, this must be a uuid, this must be a number. Written once so the messages stay
 * consistent — an operator reading a review queue sees the same wording whichever pack failed.
 */
public final class LoaderValues {

    private LoaderValues() {}

    public static boolean isPresent(@Nullable String value) {
        return value != null && !value.isBlank();
    }

    public static boolean isBlank(@Nullable String value) {
        return !isPresent(value);
    }

    /**
     * Requires a uuid, naming the business key that would otherwise have supplied it. Saying "or a
     * locationCode that resolves to one" is what tells an operator the real fault is a name that
     * matched nothing, rather than a missing column they never meant to fill in.
     */
    public static void requireUuid(
            @Nullable String value, String field, @Nullable String resolvedFrom, @NonNull List<String> errors) {
        if (isBlank(value)) {
            errors.add(
                    resolvedFrom == null
                            ? field + " is required"
                            : "%s is required (or %s)".formatted(field, resolvedFrom));
            return;
        }
        try {
            UUID.fromString(value.trim());
        } catch (IllegalArgumentException _) {
            errors.add(field + " must be a valid UUID");
        }
    }

    /** Requires an integer, or nothing at all — for optional numeric columns. */
    public static void requireIntegerOrBlank(@Nullable String value, String field, @NonNull List<String> errors) {
        if (isBlank(value)) {
            return;
        }
        try {
            Integer.parseInt(value.trim());
        } catch (NumberFormatException _) {
            errors.add(field + " must be a whole number");
        }
    }
}
