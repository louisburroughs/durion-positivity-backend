package com.positivity.supplier.internal.exception;

/**
 * Thrown when an admin mutation collides with existing configuration state: YAML-managed
 * profile mutation (ADR-0050 §6), duplicate {@code supplierRef}/auth-config name/account
 * slot/capability binding, or deleting/renaming an auth config still referenced by endpoint
 * bindings. 409 material per ADR-0017.
 */
public class SupplierConflictException extends RuntimeException {

    public static final String PROFILE_YAML_MANAGED = "SUPPLIER_PROFILE_YAML_MANAGED";
    public static final String SUPPLIER_REF_CONFLICT = "SUPPLIER_REF_CONFLICT";
    public static final String AUTH_CONFIG_NAME_CONFLICT = "SUPPLIER_AUTH_CONFIG_NAME_CONFLICT";
    public static final String AUTH_CONFIG_IN_USE = "SUPPLIER_AUTH_CONFIG_IN_USE";
    public static final String ACCOUNT_SLOT_CONFLICT = "SUPPLIER_ACCOUNT_SLOT_CONFLICT";
    public static final String BINDING_CAPABILITY_CONFLICT = "SUPPLIER_BINDING_CAPABILITY_CONFLICT";

    private final String code;

    public SupplierConflictException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
