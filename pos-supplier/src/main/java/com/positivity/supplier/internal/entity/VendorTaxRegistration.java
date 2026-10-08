package com.positivity.supplier.internal.entity;

import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * One tax registration as stored in {@code supplier_vendor.tax_registrations} (jsonb, #2516, #2621).
 *
 * <p>The number itself is never here in clear (Security ruling on #2617, ruling 3): it is sealed by
 * {@link VendorTaxIdCipher} for this tenant, vendor and registration and held as {@code numberCiphertext}.
 * {@code last4} is stored next to it so a masked read never decrypts. No element has a {@code number} key;
 * {@code V4} encrypted the elements written before this shape.
 *
 * @param registrationId UUIDv7, stable for the life of the registration; an update that keeps it keeps the
 *     stored number
 * @param scheme the registration scheme, e.g. {@code GST_HST}, {@code EIN}, {@code SSN}
 * @param region the issuing region where the scheme is regional
 * @param last4 the masked derivative ({@link #last4Of}); {@code null} under 8 alphanumerics. CONFIDENTIAL
 * @param numberCiphertext the {@link VendorTaxIdCipher} envelope, base64
 */
public record VendorTaxRegistration(
        @NonNull UUID registrationId,
        @NonNull String scheme,
        @Nullable String region,
        @Nullable String last4,
        @NonNull String numberCiphertext) {

    /** Fewer alphanumerics than this and {@code last4} is {@code null}, so a short number never shows half of itself. */
    public static final int LAST4_MINIMUM_LENGTH = 8;

    /**
     * The masked derivative of a registration number (Security ruling on #2617, ruling 2): every character
     * that is not an ASCII letter or digit is removed, then the last four are kept; {@code null} when fewer
     * than {@value #LAST4_MINIMUM_LENGTH} remain. pos-supplier is its only author: every consumer receives the
     * stored value and none derives it again. The outbox scrub ({@code V5}) applies the same rule in SQL.
     */
    public static @Nullable String last4Of(@Nullable String number) {
        if (number == null) {
            return null;
        }
        String alphanumerics = number.replaceAll("[^A-Za-z0-9]", "");
        if (alphanumerics.length() < LAST4_MINIMUM_LENGTH) {
            return null;
        }
        return alphanumerics.substring(alphanumerics.length() - 4);
    }
}
