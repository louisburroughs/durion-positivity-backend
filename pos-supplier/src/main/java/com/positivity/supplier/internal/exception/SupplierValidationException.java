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

    private final String code;

    public SupplierValidationException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
