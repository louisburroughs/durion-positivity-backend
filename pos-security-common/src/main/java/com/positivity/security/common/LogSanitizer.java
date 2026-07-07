package com.positivity.security.common;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Neutralises user-controlled values before they are written to application logs.
 *
 * <p>Directly logging request-supplied strings enables log forging / log injection
 * (CWE-117): an attacker can embed CR/LF sequences to inject fabricated log lines, or
 * other control characters to corrupt log processors and terminals. This helper strips
 * carriage returns, line feeds and other ISO control characters so any value that
 * originates from an HTTP request is safe to interpolate into a log statement.
 *
 * <pre>{@code
 * log.info("browseParties name={}", LogSanitizer.forLog(name));
 * }</pre>
 *
 * <p>Only values that can carry attacker-controlled text need sanitising. Numbers,
 * booleans, enums and UUIDs cannot contain control characters, but wrapping a
 * tainted value here also makes the sanitisation explicit at the call site.
 */
public final class LogSanitizer {

    /** Matches CR, LF, tab and every other ASCII control character. */
    private static final String CONTROL_CHARS = "[\\p{Cntrl}]";

    private LogSanitizer() {}

    /**
     * Return a log-safe representation of {@code value} with CR, LF and other control
     * characters replaced by {@code '_'}. {@code null} is rendered as the literal
     * {@code "null"} so the result is never {@code null} and can be passed straight to
     * SLF4J.
     */
    @NonNull
    public static String forLog(@Nullable Object value) {
        if (value == null) {
            return "null";
        }
        return value.toString().replaceAll(CONTROL_CHARS, "_");
    }
}
