package com.positivity.accounting.internal.entity;

import java.text.Normalizer;
import java.util.Locale;
import org.jspecify.annotations.NonNull;

/**
 * The one normalisation of a vendor bill number (#2501, BR-1; ADR-0070 Decision 4).
 *
 * <p>Two bills from the same vendor on the same date are the same document when their numbers
 * differ only in case, spacing, punctuation, compatibility forms (full-width digits and letters)
 * or leading zeros. {@link VendorBill#setBillNumber} stores the result as {@code bill_number_key},
 * and the partial unique index {@code uq_vendor_bill_duplicate_rule} is built on it. This class is
 * the key's only producer once {@code V4__vendor_bill_duplicate_rule.sql} has backfilled the rows
 * that predate it.
 */
public final class VendorBillNumbers {

    /** Width of {@code vendor_bill.bill_number_key}, in characters (code points) as Postgres counts them. */
    public static final int MAX_KEY_LENGTH = 255;

    private VendorBillNumbers() {}

    /**
     * Normalises a bill number into its duplicate-rule key. In order: Unicode NFKC, upper case
     * ({@link Locale#ROOT}), letters and digits only, leading zeros removed while more than one
     * character remains, at most {@value #MAX_KEY_LENGTH} characters.
     *
     * @param billNumber the bill number as the vendor or the generator wrote it
     * @return the key; empty when the number holds no letter or digit, which is a legal key
     */
    public static @NonNull String normalise(@NonNull String billNumber) {
        String upper = Normalizer.normalize(billNumber, Normalizer.Form.NFKC).toUpperCase(Locale.ROOT);
        StringBuilder kept = new StringBuilder(upper.length());
        upper.codePoints().filter(Character::isLetterOrDigit).forEach(kept::appendCodePoint);

        int start = 0;
        while (start < kept.length() - 1 && kept.charAt(start) == '0') {
            start++;
        }
        String key = kept.substring(start);
        if (key.codePointCount(0, key.length()) <= MAX_KEY_LENGTH) {
            return key;
        }
        return key.substring(0, key.offsetByCodePoints(0, MAX_KEY_LENGTH));
    }
}
