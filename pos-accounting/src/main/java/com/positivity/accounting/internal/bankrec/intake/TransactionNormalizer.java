package com.positivity.accounting.internal.bankrec.intake;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Semantic normalization and fingerprinting at intake (SPEC §3.2, §4.5; story S2, #2301). The
 * adapter normalizes formats; this normalizes semantics — and only what the source said: nothing is
 * inferred.
 *
 * <ul>
 *   <li>Amounts are stored at 4 decimal places exactly as delivered (R3): {@code 1234.5} becomes
 *       {@code 1234.5000}; an amount with more than four significant decimals is refused rather than
 *       rounded.
 *   <li>{@link #normalizeDescription} is an upper-cased, punctuation-stripped, whitespace-collapsed
 *       copy used only for the fingerprint and (story S4) candidate scoring; the description itself
 *       is kept verbatim.
 *   <li>{@link #fingerprint} is the SHA-256 of {@code glAccountId | transactionDate |
 *       signedAmount(4 dp) | normalizedDescription | reference-or-checkNumber}.
 * </ul>
 */
public final class TransactionNormalizer {

    /** Storage scale of every bank amount ({@code numeric(19,4)}). */
    public static final int AMOUNT_SCALE = 4;

    /** Column width of {@code bank_transaction.normalized_description}. */
    static final int NORMALIZED_DESCRIPTION_LENGTH = 500;

    private static final Pattern PUNCTUATION = Pattern.compile("[\\p{P}\\p{S}]");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final String SEPARATOR = "|";

    private TransactionNormalizer() {}

    /**
     * Scales an amount to the storage scale without changing its value.
     *
     * @throws ArithmeticException when the amount carries more than four significant decimals
     */
    public static @NonNull BigDecimal scaleAmount(@NonNull BigDecimal amount) {
        return amount.setScale(AMOUNT_SCALE, RoundingMode.UNNECESSARY);
    }

    /** Upper-cased, punctuation and symbols stripped, whitespace collapsed and trimmed; never null. */
    public static @NonNull String normalizeDescription(@Nullable String description) {
        if (description == null) {
            return "";
        }
        String upper = description.toUpperCase(Locale.ROOT);
        String stripped = PUNCTUATION.matcher(upper).replaceAll("");
        String collapsed = WHITESPACE.matcher(stripped).replaceAll(" ").trim();
        return collapsed.length() > NORMALIZED_DESCRIPTION_LENGTH
                ? collapsed.substring(0, NORMALIZED_DESCRIPTION_LENGTH)
                : collapsed;
    }

    /**
     * The dedupe fingerprint of §3.2: 64 lowercase hex characters. The reference wins over the check
     * number when both are present; a blank value counts as absent.
     */
    public static @NonNull String fingerprint(
            @NonNull UUID glAccountId,
            @NonNull LocalDate transactionDate,
            @NonNull BigDecimal signedAmount,
            @NonNull String normalizedDescription,
            @Nullable String reference,
            @Nullable String checkNumber) {
        String key = glAccountId
                + SEPARATOR
                + transactionDate
                + SEPARATOR
                + scaleAmount(signedAmount).toPlainString()
                + SEPARATOR
                + normalizedDescription
                + SEPARATOR
                + referenceOrCheckNumber(reference, checkNumber);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(key.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java platform", e);
        }
    }

    private static String referenceOrCheckNumber(@Nullable String reference, @Nullable String checkNumber) {
        if (reference != null && !reference.isBlank()) {
            return reference.trim();
        }
        if (checkNumber != null && !checkNumber.isBlank()) {
            return checkNumber.trim();
        }
        return "";
    }
}
