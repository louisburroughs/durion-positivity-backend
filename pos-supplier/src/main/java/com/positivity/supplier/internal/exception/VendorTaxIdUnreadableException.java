package com.positivity.supplier.internal.exception;

import org.jspecify.annotations.Nullable;

/**
 * A vendor's stored tax-registration number exists but cannot be decrypted (#2621, Security ruling on #2617,
 * ruling 3): the envelope is malformed, names a key id this deployment has no key for, or fails
 * authentication. Authentication covers the tenant, vendor and registration the ciphertext was sealed for,
 * so a ciphertext copied into another row lands here too.
 *
 * <p>Answered as 500 {@link #CODE} with a generic message: the caller can do nothing about it, and the
 * detail is for an operator. The message carries the key id and the failure kind only, never ciphertext,
 * key material or any part of the number.
 */
public class VendorTaxIdUnreadableException extends RuntimeException {

    /** The wire code; the reveal answers 500 with it. */
    public static final String CODE = "SUPPLIER_VENDOR_TAX_ID_UNREADABLE";

    private final String failure;
    private final transient @Nullable String keyId;

    public VendorTaxIdUnreadableException(
            String failure, @Nullable String keyId, String message, @Nullable Throwable cause) {
        super(message, cause);
        this.failure = failure;
        this.keyId = keyId;
    }

    public String getCode() {
        return CODE;
    }

    /** {@code MALFORMED_ENVELOPE}, {@code UNKNOWN_KEY_ID} or {@code AUTHENTICATION_FAILED}. */
    public String getFailure() {
        return failure;
    }

    /** The key id the envelope names; {@code null} when the envelope was too malformed to name one. */
    public @Nullable String getKeyId() {
        return keyId;
    }
}
