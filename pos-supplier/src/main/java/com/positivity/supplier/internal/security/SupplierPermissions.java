package com.positivity.supplier.internal.security;

/**
 * Permission constants for the supplier module (ADR-0025, ADR-0040;
 * durion-positivity-backend#1222). Names must match {@code permissions.yaml} and the
 * gateway/security-service catalogs exactly.
 */
public final class SupplierPermissions {

    /** Read vendor profiles and their auth configs, accounts, and endpoint bindings. */
    public static final String PROFILE_READ = "supplier:profile:read";

    /** Create, edit, and delete vendor profiles and their child configuration. */
    public static final String PROFILE_WRITE = "supplier:profile:write";

    /**
     * Read the exchange-audit trail, including stored payload content (ADR-0050 §7, catalog bit 445).
     *
     * <p>Deliberately <strong>not</strong> implied by {@link #PROFILE_READ}. ADR-0050 §7 requires payload
     * access to be "tighter than profile admin", and the reason is concrete: a profile administrator
     * configures how this deployment talks to a supplier, which is not the same authority as reading the
     * commercial documents that flowed over it — prices, order volumes, and account terms. Anyone who
     * grants this permission is granting sight of a trading partner's business, and every payload read
     * lands in {@code supplier_audit_access} under the reader's name.
     */
    public static final String AUDIT_READ = "supplier:audit:read";

    /**
     * Read vendor price-catalog (PRICAT) import bookkeeping and the unmatched-line quarantine
     * (ADR-0053 §5/§7, catalog bit 448).
     *
     * <p>Separate from {@link #PROFILE_READ} because it answers a different question: profile read
     * says how this deployment is configured to talk to a vendor, while this says what that vendor
     * sent and how much of it the catalog could not price. It is deliberately <strong>not</strong>
     * {@link #AUDIT_READ} either — that permission grants sight of raw commercial payloads, and an
     * operator working the unmatched-line worklist should not need it.
     */
    public static final String PRICECATALOG_READ = "supplier:pricecatalog:read";

    /**
     * Trigger an on-demand PRICAT import (catalog bit 449).
     *
     * <p>Write-shaped even though it fetches: a trigger makes an outbound commercial call to a
     * trading partner and publishes an import's worth of events, so it is gated apart from reading
     * the results.
     */
    public static final String PRICECATALOG_IMPORT = "supplier:pricecatalog:import";

    private SupplierPermissions() {}
}
