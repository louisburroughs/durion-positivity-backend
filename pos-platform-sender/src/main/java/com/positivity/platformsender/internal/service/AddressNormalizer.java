package com.positivity.platformsender.internal.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Turns a stored contact point into a deliverable address, and hashes it.
 *
 * <p>pos-people-contact stores values only trimmed, so this normalizes at send time: an email is
 * trimmed and lowercased, a phone number becomes E.164 ({@code +} and 8 to 15 digits, the first not
 * 0), which is what End User Messaging requires. A phone number may carry spaces, dashes, dots and
 * parentheses; anything else (letters, stray symbols) makes it undeliverable rather than stripped. A number stored without a country code gets the configured default
 * when it has ten digits (a national NANP number), or is taken as already carrying that code when it
 * has ten more digits than the code; anything else is not deliverable rather than guessed at.
 *
 * <p>{@link #hash} is the SHA-256 (lowercase hex, UTF-8) of the normalized address. For an address
 * already in this form it is byte-for-byte what pos-customer's {@code MarketingAddressNormalizer}
 * computes, so the {@code addressHash} FI-2 returns and the suppression entry a bounce creates name
 * the same address.
 */
public final class AddressNormalizer {

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    /** A stored phone number: an optional leading {@code +}, digits and common separators only. */
    private static final Pattern PHONE = Pattern.compile("^\\+?[0-9 ().\\-]+$");

    private static final int NATIONAL_NUMBER_DIGITS = 10;
    private static final int E164_MIN_DIGITS = 8;
    private static final int E164_MAX_DIGITS = 15;

    private AddressNormalizer() {}

    /** The trimmed, lowercased email, when it looks deliverable. */
    public static @NonNull Optional<String> email(@Nullable String stored) {
        if (stored == null) {
            return Optional.empty();
        }
        String normalized = stored.trim().toLowerCase(Locale.ROOT);
        return EMAIL.matcher(normalized).matches() ? Optional.of(normalized) : Optional.empty();
    }

    /** The E.164 form of a stored phone number, when one can be derived without guessing. */
    public static @NonNull Optional<String> e164(@Nullable String stored, @NonNull String defaultCountryCode) {
        if (stored == null) {
            return Optional.empty();
        }
        String trimmed = stored.trim();
        if (!PHONE.matcher(trimmed).matches()) {
            // Letters or stray symbols: stripping them would silently produce a different number.
            return Optional.empty();
        }
        String digits = trimmed.replaceAll("\\D", "");
        if (digits.isEmpty()) {
            return Optional.empty();
        }
        String countryCode = defaultCountryCode.replaceAll("\\D", "");
        String international;
        if (trimmed.startsWith("+")) {
            international = digits;
        } else if (digits.length() == NATIONAL_NUMBER_DIGITS && !countryCode.isEmpty()) {
            international = countryCode + digits;
        } else if (!countryCode.isEmpty()
                && digits.startsWith(countryCode)
                && digits.length() == countryCode.length() + NATIONAL_NUMBER_DIGITS) {
            international = digits;
        } else {
            return Optional.empty();
        }
        if (international.length() < E164_MIN_DIGITS
                || international.length() > E164_MAX_DIGITS
                || international.charAt(0) == '0') {
            // E.164: 8 to 15 digits, and no country calling code starts with 0.
            return Optional.empty();
        }
        return Optional.of("+" + international);
    }

    /** SHA-256 of the normalized address, lowercase hex. */
    public static @NonNull String hash(@NonNull String normalizedAddress) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(normalizedAddress.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
