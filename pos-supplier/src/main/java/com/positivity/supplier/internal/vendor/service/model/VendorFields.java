package com.positivity.supplier.internal.vendor.service.model;

import com.positivity.supplier.internal.exception.SupplierValidationException;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/** Field rules shared by the vendor request records (#2516). Each violation is a 400 {@code VALIDATION_ERROR}. */
final class VendorFields {

    /** {@code vendorNumber}: a letter or digit, then up to 29 letters, digits or hyphens; upper case. */
    static final Pattern VENDOR_NUMBER = Pattern.compile("^[A-Z0-9][A-Z0-9-]{0,29}$");

    /** {@code DUE_ON_RECEIPT} or {@code NET<n>}, n 1–120: the format purchase orders use for {@code paymentTermsId}. */
    static final Pattern PAYMENT_TERMS = Pattern.compile("^(DUE_ON_RECEIPT|NET([1-9]|[1-9][0-9]|1[01][0-9]|120))$");

    static final Pattern CURRENCY = Pattern.compile("^[A-Z]{3}$");

    static final Pattern COUNTRY = Pattern.compile("^[A-Z]{2}$");

    static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    /** A reason or note says something: at least this many characters once trimmed (SPEC §4.9). */
    static final int MIN_NOTE_LENGTH = 10;

    static final int MAX_NOTE_LENGTH = 1000;

    static final int MAX_NAME_LENGTH = 255;

    private VendorFields() {}

    static String required(@Nullable String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            throw invalid(field + " must not be blank");
        }
        if (value.length() > maxLength) {
            throw invalid(field + " must be at most " + maxLength + " characters");
        }
        return value;
    }

    static @Nullable String optional(@Nullable String value, String field, int maxLength) {
        if (value == null) {
            return null;
        }
        if (value.isBlank()) {
            throw invalid(field + " must not be blank when present");
        }
        if (value.length() > maxLength) {
            throw invalid(field + " must be at most " + maxLength + " characters");
        }
        return value;
    }

    static String matching(@Nullable String value, String field, Pattern pattern, String shape) {
        if (value == null || !pattern.matcher(value).matches()) {
            throw invalid(field + " must be " + shape);
        }
        return value;
    }

    static String note(@Nullable String value, String field) {
        if (value == null || value.strip().length() < MIN_NOTE_LENGTH) {
            throw invalid(field + " must be at least " + MIN_NOTE_LENGTH + " characters");
        }
        if (value.length() > MAX_NOTE_LENGTH) {
            throw invalid(field + " must be at most " + MAX_NOTE_LENGTH + " characters");
        }
        return value;
    }

    static SupplierValidationException invalid(String message) {
        return new SupplierValidationException(SupplierValidationException.VALIDATION_ERROR, message);
    }
}
