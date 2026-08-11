package com.positivity.customer.internal.service;

import java.util.regex.Pattern;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Masks contact details and long digit runs in free-text interaction bodies
 * before they
 * leave the service (Story #1141, DECISION-INVENTORY-006).
 *
 * <p>
 * Interaction bodies are typed by humans, so they accumulate the things a CRM
 * timeline
 * should not hand back verbatim: a customer's email dictated over the phone, a
 * card number
 * jotted into a call note. Redaction happens on read rather than on write so
 * the original
 * text stays available to a compliance export that is entitled to it.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class InteractionBodyRedactor {

    private static final Pattern EMAIL = Pattern.compile("(?<![\\w.+-])[\\w.+-]{1,64}@[\\w-]{1,63}\\.[\\w.-]{1,251}");

    /**
     * Minimum digits in a run (single {@code space}/{@code .}/{@code -} separators
     * allowed between
     * digits) before it is masked. Shorter runs are left alone because they are far
     * more often a
     * mileage, a part number, or a price than a phone or card number. Matches the
     * retired
     * {@code (?<!\d)(?:\d[ .-]?){7,}\d(?!\d)} regex, whose repeated group could
     * overflow the stack
     * on large bodies (java:S5998) — digit runs are now found with a linear scan
     * instead.
     */
    private static final int MIN_MASKED_DIGITS = 8;

    private static final String NUMBER_MASK = "[redacted-number]";

    public static @Nullable String redact(@Nullable String body) {
        if (body == null || body.isBlank()) {
            return body;
        }
        String masked = EMAIL.matcher(body).replaceAll("[redacted-email]");
        return maskLongDigitRuns(masked);
    }

    /**
     * Whether redaction would change the text — used to flag partially masked
     * bodies in UIs.
     */
    public static boolean containsRedactableContent(@NonNull String body) {
        return EMAIL.matcher(body).find() || containsLongDigitRun(body);
    }

    private static @NonNull String maskLongDigitRuns(@NonNull String text) {
        StringBuilder out = new StringBuilder(text.length());
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (!isAsciiDigit(c)) {
                out.append(c);
                i++;
                continue;
            }
            int runEnd = endOfDigitRun(text, i);
            if (countDigits(text, i, runEnd) >= MIN_MASKED_DIGITS) {
                out.append(NUMBER_MASK);
            } else {
                out.append(text, i, runEnd);
            }
            i = runEnd;
        }
        return out.toString();
    }

    private static boolean containsLongDigitRun(@NonNull String text) {
        int i = 0;
        while (i < text.length()) {
            if (!isAsciiDigit(text.charAt(i))) {
                i++;
                continue;
            }
            int runEnd = endOfDigitRun(text, i);
            if (countDigits(text, i, runEnd) >= MIN_MASKED_DIGITS) {
                return true;
            }
            i = runEnd;
        }
        return false;
    }

    /**
     * End (exclusive) of the digit run starting at {@code start}: digits joined by
     * at most one
     * {@code space}/{@code .}/{@code -} each. Always ends on a digit, so trailing
     * separators are
     * left outside the run — exactly the span the retired regex matched.
     */
    private static int endOfDigitRun(@NonNull String text, int start) {
        int end = start + 1;
        int probe = start + 1;
        while (probe < text.length()) {
            char c = text.charAt(probe);
            if (isAsciiDigit(c)) {
                probe++;
                end = probe;
            } else if (isSeparator(c) && probe + 1 < text.length() && isAsciiDigit(text.charAt(probe + 1))) {
                probe += 2;
                end = probe;
            } else {
                break;
            }
        }
        return end;
    }

    private static int countDigits(@NonNull String text, int from, int to) {
        int digits = 0;
        for (int i = from; i < to; i++) {
            if (isAsciiDigit(text.charAt(i))) {
                digits++;
            }
        }
        return digits;
    }

    private static boolean isAsciiDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isSeparator(char c) {
        return c == ' ' || c == '.' || c == '-';
    }
}
