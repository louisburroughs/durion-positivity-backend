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

    /**
     * A manual resolution was attempted on a transmission that is not awaiting review
     * (ADR-0052 §4).
     *
     * <p>A <em>runtime state</em> collision rather than a configuration one, which is why it does
     * not reuse any of the codes above: the caller's view of the transmission is simply stale, and
     * refreshing it is the fix. Telling them a capability binding conflicts would send them to an
     * admin screen that has nothing to do with the problem.
     */
    public static final String TRANSMISSION_STATE_CONFLICT = "SUPPLIER_TRANSMISSION_STATE_CONFLICT";

    /**
     * More than one replicated product carries the SKU the availability read was asked about.
     * pos-catalog's uniqueness makes this a replication defect, and it is refused rather than
     * guessed — answering with an arbitrary product's availability would look right and be about
     * the wrong article.
     */
    public static final String PRODUCT_SKU_AMBIGUOUS = "SUPPLIER_PRODUCT_SKU_AMBIGUOUS";

    /** The vendor number is already used by another vendor of the tenant (#2516). */
    public static final String VENDOR_NUMBER_TAKEN = "SUPPLIER_VENDOR_NUMBER_TAKEN";

    /** The vendor already has a remit-to change waiting for a decision; at most one may (#2516). */
    public static final String VENDOR_REMIT_CHANGE_PENDING = "SUPPLIER_VENDOR_REMIT_CHANGE_PENDING";

    /** The remit-to change was already approved or rejected; both are terminal (#2516). */
    public static final String VENDOR_REMIT_CHANGE_NOT_PENDING = "SUPPLIER_VENDOR_REMIT_CHANGE_NOT_PENDING";

    /**
     * The caller's view of a vendor is stale (#2516): an update carried an old {@code version}, or a
     * status command asked for the status the vendor already has. The platform's generic optimistic-lock
     * code, so a client handles both the same way — refresh and retry.
     */
    public static final String CONFLICT = "CONFLICT";

    private final String code;

    public SupplierConflictException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
