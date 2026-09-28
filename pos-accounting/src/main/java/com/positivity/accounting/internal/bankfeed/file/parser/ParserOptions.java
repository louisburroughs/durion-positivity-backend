package com.positivity.accounting.internal.bankfeed.file.parser;

import com.positivity.accounting.internal.bankrec.intake.BankRecErrorCode;
import com.positivity.accounting.internal.bankrec.intake.BankRecException;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * The parser options of an import (SPEC-manual-bank-reconciliation §3.3; story S3, #2302), each with
 * the F2 default: UTF-8, comma-delimited, ISO and US dates, decimal point, signed amount.
 *
 * @param charset the file's text encoding ({@code encoding})
 * @param delimiter the field delimiter ({@code delimiter}: one character, or {@code TAB})
 * @param datePattern a {@link DateTimeFormatter} pattern ({@code dateFormat}); null = the F2 defaults
 *     {@code yyyy-MM-dd}, {@code M/d/yyyy}, {@code MM/dd/yyyy}, {@code yyyy/MM/dd}
 * @param decimalFormat the decimal separator ({@code decimalFormat})
 * @param signConvention how the file states direction ({@code signConvention})
 */
public record ParserOptions(
        @NonNull Charset charset,
        char delimiter,
        @Nullable String datePattern,
        @NonNull DecimalFormatOption decimalFormat,
        @NonNull SignConvention signConvention) {

    /** The F2 defaults. */
    public static @NonNull ParserOptions defaults() {
        return new ParserOptions(
                StandardCharsets.UTF_8, ',', null, DecimalFormatOption.DECIMAL_POINT, SignConvention.SIGNED_AMOUNT);
    }

    /**
     * Reads the request's option strings, each null for its default.
     *
     * @throws BankRecException {@code VALIDATION_ERROR} naming each invalid option
     */
    public static @NonNull ParserOptions of(
            @Nullable String encoding,
            @Nullable String delimiter,
            @Nullable String dateFormat,
            @Nullable String decimalFormat,
            @Nullable String signConvention) {
        Map<String, String> errors = new LinkedHashMap<>();
        Charset charset = StandardCharsets.UTF_8;
        if (encoding != null) {
            try {
                charset = Charset.forName(encoding.trim());
            } catch (IllegalCharsetNameException | UnsupportedCharsetException unknown) {
                errors.put("encoding", "unknown character set");
            }
        }
        char separator = ',';
        if (delimiter != null) {
            if ("TAB".equalsIgnoreCase(delimiter) || "\t".equals(delimiter) || "\\t".equals(delimiter)) {
                separator = '\t';
            } else if (delimiter.length() == 1 && delimiter.charAt(0) != '"' && !isLineBreak(delimiter.charAt(0))) {
                separator = delimiter.charAt(0);
            } else {
                errors.put("delimiter", "one character other than a quote or a line break, or TAB");
            }
        }
        String pattern = null;
        if (dateFormat != null) {
            if (dateFormat.isBlank() || dateFormat.length() > 32) {
                errors.put("dateFormat", "a date pattern of at most 32 characters");
            } else {
                try {
                    DateTimeFormatter.ofPattern(dateFormat, Locale.ROOT);
                    pattern = dateFormat;
                } catch (IllegalArgumentException invalid) {
                    errors.put("dateFormat", "not a valid date pattern");
                }
            }
        }
        DecimalFormatOption decimal = parseEnum(
                DecimalFormatOption.class, decimalFormat, "decimalFormat", errors, DecimalFormatOption.DECIMAL_POINT);
        SignConvention convention =
                parseEnum(SignConvention.class, signConvention, "signConvention", errors, SignConvention.SIGNED_AMOUNT);
        if (!errors.isEmpty()) {
            throw new BankRecException(BankRecErrorCode.VALIDATION_ERROR, "The parser options are invalid", errors);
        }
        return new ParserOptions(charset, separator, pattern, decimal, convention);
    }

    private static boolean isLineBreak(char c) {
        return c == '\n' || c == '\r';
    }

    private static <E extends Enum<E>> E parseEnum(
            Class<E> type, @Nullable String value, String field, Map<String, String> errors, E fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            StringBuilder allowed = new StringBuilder();
            for (E constant : type.getEnumConstants()) {
                allowed.append(allowed.isEmpty() ? "" : ", ").append(constant.name());
            }
            errors.put(field, "one of " + allowed);
            return fallback;
        }
    }

    /** The delimiter as stored in {@code bank_import.delimiter}. */
    public @NonNull String delimiterCode() {
        return delimiter == '\t' ? "TAB" : String.valueOf(delimiter);
    }
}
