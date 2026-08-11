package com.positivity.supplier.internal.exception;

/**
 * Typed configuration failure raised <em>before any network use</em> (ADR-0050 §3/§4/§5):
 * unknown supplier alias, disabled profile, unbound/disabled capability, missing
 * billing/delivery account mapping, or an unresolvable secret reference. Supplier
 * orchestration (CAP-317 slice 3) consumes the {@code code} as a typed pre-flight outcome
 * (e.g. {@code CAPABILITY_NOT_CONFIGURED}) — these are configuration states, never NPE/500
 * material. Messages carry references and aliases only, never resolved secret values.
 */
public class SupplierConfigurationException extends RuntimeException {

    public static final String UNKNOWN_SUPPLIER = "SUPPLIER_UNKNOWN";
    public static final String PROFILE_DISABLED = "SUPPLIER_PROFILE_DISABLED";

    /**
     * Prefixed {@code SUPPLIER_} like every other code here, so the wire code names its owning
     * domain. Deliberately distinct from the unprefixed {@code CAPABILITY_NOT_CONFIGURED}
     * constant on the {@code service.model} outcome enums: that one is a
     * <em>successful-response status</em> of a capability call, this one is an
     * {@code ApiError.code}. Renaming the enum constant would break the published contract;
     * renaming this code does not, because nothing had mapped it to HTTP yet.
     */
    public static final String CAPABILITY_NOT_CONFIGURED = "SUPPLIER_CAPABILITY_NOT_CONFIGURED";

    public static final String MISSING_BILLING_ACCOUNT = "SUPPLIER_MISSING_BILLING_ACCOUNT";
    public static final String MISSING_DELIVERY_MAPPING = "SUPPLIER_MISSING_DELIVERY_MAPPING";
    public static final String AUTH_CONFIG_MISSING = "SUPPLIER_AUTH_CONFIG_MISSING";
    public static final String SECRET_REFERENCE_INVALID = "SUPPLIER_SECRET_REFERENCE_INVALID";
    public static final String UNKNOWN_SECRET_SCHEME = "SUPPLIER_UNKNOWN_SECRET_SCHEME";
    public static final String SECRET_NOT_FOUND = "SUPPLIER_SECRET_NOT_FOUND";
    public static final String YAML_BOOTSTRAP_INVALID = "SUPPLIER_YAML_BOOTSTRAP_INVALID";

    private final String code;

    public SupplierConfigurationException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
