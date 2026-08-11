package com.positivity.supplier.internal.security;

/**
 * Permission constants for supplier vendor-profile administration (ADR-0025, ADR-0040;
 * durion-positivity-backend#1222). Names must match {@code permissions.yaml} and the
 * gateway/security-service catalogs exactly.
 *
 * <p>{@code supplier:audit:read} (exchange-audit read API, CAP-317 slice 3) is declared in
 * {@code permissions.yaml} and the catalogs alongside these but gets its constant with the
 * audit controllers.
 */
public final class SupplierPermissions {

    /** Read vendor profiles and their auth configs, accounts, and endpoint bindings. */
    public static final String PROFILE_READ = "supplier:profile:read";

    /** Create, edit, and delete vendor profiles and their child configuration. */
    public static final String PROFILE_WRITE = "supplier:profile:write";

    private SupplierPermissions() {}
}
