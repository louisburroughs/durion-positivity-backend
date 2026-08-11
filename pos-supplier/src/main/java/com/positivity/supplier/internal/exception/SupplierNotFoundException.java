package com.positivity.supplier.internal.exception;

/**
 * Thrown when a supplier-domain resource referenced by id does not exist (or does not belong
 * to the addressed profile). 404 material per ADR-0017; the {@code code} is the
 * machine-readable {@code ApiError} code.
 */
public class SupplierNotFoundException extends RuntimeException {

    public static final String PROFILE_NOT_FOUND = "SUPPLIER_PROFILE_NOT_FOUND";
    public static final String AUTH_CONFIG_NOT_FOUND = "SUPPLIER_AUTH_CONFIG_NOT_FOUND";
    public static final String ACCOUNT_NOT_FOUND = "SUPPLIER_ACCOUNT_NOT_FOUND";
    public static final String BINDING_NOT_FOUND = "SUPPLIER_BINDING_NOT_FOUND";

    private final String code;

    public SupplierNotFoundException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
