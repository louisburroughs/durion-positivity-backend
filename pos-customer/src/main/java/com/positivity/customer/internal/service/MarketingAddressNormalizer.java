package com.positivity.customer.internal.service;

import com.positivity.customer.internal.enums.MarketingChannel;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.NonNull;

/**
 * Normalizes and hashes marketing addresses so suppression lookups are stable (Story #1140).
 *
 * <p>Normalization has to be identical on the write and the read path or a suppressed address
 * silently stops matching, so both go through here rather than being re-implemented at each
 * call site. Email is lower-cased and trimmed; SMS is reduced to digits with a leading
 * {@code +} preserved, which makes {@code (555) 010-0100} and {@code 5550100100} the same
 * address.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class MarketingAddressNormalizer {

    private static final int HINT_MAX = 120;

    public static @NonNull String normalize(@NonNull MarketingChannel channel, @NonNull String address) {
        String trimmed = address.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("address must not be blank");
        }
        return channel == MarketingChannel.EMAIL ? trimmed.toLowerCase(Locale.ROOT) : normalizePhone(trimmed);
    }

    /** SHA-256 hex of the normalized address — the suppression table's lookup key. */
    public static @NonNull String hash(@NonNull MarketingChannel channel, @NonNull String address) {
        String normalized = normalize(channel, address);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(normalized.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required for suppression address hashing", e);
        }
    }

    /**
     * Masked form retained alongside the hash so an admin reviewing the suppression list can
     * recognize an entry without the table holding a usable address.
     */
    public static @NonNull String hint(@NonNull MarketingChannel channel, @NonNull String address) {
        String normalized = normalize(channel, address);
        String masked = channel == MarketingChannel.EMAIL ? maskEmail(normalized) : maskPhone(normalized);
        return masked.length() > HINT_MAX ? masked.substring(0, HINT_MAX) : masked;
    }

    private static String normalizePhone(String value) {
        boolean international = value.startsWith("+");
        String digits = value.replaceAll("\\D", "");
        if (digits.isEmpty()) {
            throw new IllegalArgumentException("phone address must contain digits");
        }
        return international ? "+" + digits : digits;
    }

    private static String maskEmail(String email) {
        int at = email.indexOf('@');
        if (at <= 0) {
            return "***";
        }
        return email.charAt(0) + "***" + email.substring(at);
    }

    private static String maskPhone(String phone) {
        int keep = Math.min(4, phone.length());
        return "***" + phone.substring(phone.length() - keep);
    }
}
