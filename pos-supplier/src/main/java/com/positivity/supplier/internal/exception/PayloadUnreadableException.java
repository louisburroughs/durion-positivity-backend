package com.positivity.supplier.internal.exception;

/**
 * An exchange-audit payload exists but cannot be decrypted (binding decision 1): the envelope is
 * malformed or truncated, its key id is not configured, or authentication failed because the
 * ciphertext or its header was tampered with.
 *
 * <p>Deliberately <strong>not</strong> a {@code SupplierConfigurationException}. A missing key is a
 * configuration problem, but a failed GCM tag is potential evidence of tampering, and collapsing the
 * two would let a real integrity failure be read as routine misconfiguration. It is also deliberately
 * distinct from "no payload captured", which is a normal state for a {@code METADATA_ONLY} binding
 * and must never surface as an error.
 *
 * <p>Messages carry the envelope's key id and the failure kind only — never ciphertext, never key
 * material, never a partially decrypted fragment.
 */
public class PayloadUnreadableException extends RuntimeException {

    /** The envelope could not be parsed: wrong version byte, truncated, or impossible lengths. */
    public static final String MALFORMED_ENVELOPE = "SUPPLIER_AUDIT_PAYLOAD_ENVELOPE_MALFORMED";

    /** The envelope names a key id that this deployment has no key for. */
    public static final String UNKNOWN_KEY_ID = "SUPPLIER_AUDIT_PAYLOAD_UNKNOWN_KEY";

    /** Authentication failed — ciphertext or bound header was modified, or the key is wrong. */
    public static final String AUTHENTICATION_FAILED = "SUPPLIER_AUDIT_PAYLOAD_AUTHENTICATION_FAILED";

    private final String code;

    public PayloadUnreadableException(String code, String message) {
        super(message);
        this.code = code;
    }

    public PayloadUnreadableException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
