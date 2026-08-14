package com.positivity.supplier.internal.exception;

/**
 * Thrown when a well-formed admin request carries semantically invalid vendor profile data —
 * unknown capability/protocol-family keys, per-{@code SupplierAuthType} secret-reference
 * incompleteness, malformed secret references, unknown auth config names, invalid cron
 * schedules (ADR-0050 §3/§4). 400 material per ADR-0017.
 */
public class SupplierValidationException extends RuntimeException {

    public static final String UNKNOWN_CAPABILITY = "SUPPLIER_UNKNOWN_CAPABILITY";
    public static final String UNKNOWN_PROTOCOL_FAMILY = "SUPPLIER_UNKNOWN_PROTOCOL_FAMILY";
    public static final String AUTH_REFS_INCOMPLETE = "SUPPLIER_AUTH_REFS_INCOMPLETE";
    public static final String SECRET_REF_MALFORMED = "SUPPLIER_SECRET_REF_MALFORMED";
    public static final String BINDING_AUTH_CONFIG_UNKNOWN = "SUPPLIER_BINDING_AUTH_CONFIG_UNKNOWN";
    public static final String SCHEDULE_INVALID = "SUPPLIER_SCHEDULE_INVALID";

    /** An audit query window whose end is not after its start; it can never match anything. */
    public static final String AUDIT_WINDOW_INVALID = "SUPPLIER_AUDIT_WINDOW_INVALID";

    /**
     * A URL carrying userinfo ({@code https://user:secret@host}). Rejected rather than accepted because
     * userinfo <em>is</em> a plaintext credential, and ADR-0050 §4 is that plaintext credentials never persist —
     * a base URL is configuration, which is exactly where one would live forever.
     */
    public static final String URL_CONTAINS_CREDENTIALS = "SUPPLIER_URL_CONTAINS_CREDENTIALS";

    /**
     * A manual transmission resolution that does not carry what its action requires — today, a
     * confirmation without the vendor's own order reference (ADR-0052 §4).
     *
     * <p>The vendor reference is the evidence for the assertion being made. Without it an operator
     * is recording "the vendor has this order" with nothing anyone can check it against later,
     * which is the one thing the manual path exists to avoid.
     */
    public static final String TRANSMISSION_RESOLUTION_INCOMPLETE = "SUPPLIER_TRANSMISSION_RESOLUTION_INCOMPLETE";

    private final String code;

    public SupplierValidationException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
