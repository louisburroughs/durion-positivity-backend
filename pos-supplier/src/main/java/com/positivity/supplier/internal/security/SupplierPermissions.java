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

    /**
     * Read the purchase-order transmission ledger: what was sent to which vendor, what the vendor
     * answered, and what is stuck (ADR-0052).
     *
     * <p>Separate from {@link #PROFILE_READ} because it answers a different question — profile
     * read says how this deployment is configured to talk to a vendor, this says what we actually
     * ordered from them. Deliberately not {@link #AUDIT_READ} either: an operator working the
     * stuck-transmission queue needs the ledger, not sight of raw commercial payloads.
     */
    public static final String TRANSMISSION_READ = "supplier:transmission:read";

    /**
     * Resolve a transmission awaiting manual review (ADR-0052 §4, deny-by-default per ADR-0040).
     *
     * <p>The most consequential permission in this module. Its holder asserts, on the system's
     * behalf, that a vendor did or did not receive a purchase order — a statement about the
     * physical world that this system cannot verify and that decides whether goods get ordered
     * again. Every use is recorded with the actor's name and their free-text evidence, and
     * published as an event on the purchase-order timeline.
     */
    public static final String TRANSMISSION_RESOLVE = "supplier:transmission:resolve";

    /**
     * Ask a vendor, live, what stock it holds for a receiving location (CAP-319, ADR-0044
     * amendment 2026-08-10).
     *
     * <p>Its own permission rather than a reuse of {@link #PRICECATALOG_READ}, because reading a
     * vendor's already-staged prices and placing a call to that vendor on every customer page view
     * are different acts with different costs. A deployment that wants the price catalogue without
     * putting a trading partner's endpoint in the path of its own product pages needs to be able to
     * say so, and one permission covering both would take that choice away.
     *
     * <p>Held by the composing services rather than by people: pos-catalog asserts it when building
     * Product Detail, pos-order when quoting procurement.
     */
    public static final String STOCK_INQUIRE = "supplier:stock:inquire";

    /**
     * Read fetched vendor stock-report snapshots: what a vendor said it had, for a whole market, at
     * one moment (CAP-322).
     *
     * <p>Its own permission rather than a reuse of {@link #STOCK_INQUIRE}, because the acts differ
     * in kind: an inquiry places a live call to a trading partner, while this reads what an earlier
     * scheduled fetch already stored — no vendor is contacted. And not {@link #PROFILE_READ} either:
     * a snapshot is a received commercial document about a vendor's stock position, not
     * configuration, and it deliberately outlives the profile that produced it.
     */
    public static final String STOCK_SNAPSHOT_READ = "supplier:stocksnapshot:read";

    /**
     * Running a vendor invoice fetch on demand.
     *
     * <p>Its own permission rather than an admin catch-all: it reaches out to a vendor and can
     * create AP bills, so the people who may investigate a missing invoice are not necessarily the
     * people who may reconfigure a profile.
     */
    public static final String INVOICE_FETCH = "supplier:invoice:fetch";

    /**
     * Asking a fleet program to authorize work, and reading fleet vehicles, contracts and policies.
     *
     * <p>One permission covering the read lookups and the authorization request, because in a shop
     * they are one act: a service advisor looks a fleet vehicle up in order to ask for authorization
     * on it, and separating them would mean a role that can see a fleet contract but cannot use it.
     */
    public static final String WORKORDER_AUTHORIZE = "supplier:workorderauth:request";

    /**
     * Seeing and clearing authorizations parked for manual review.
     *
     * <p>Separate from requesting, because these are the rows where a vendor could not be reached or
     * a completion could not be signed off — money already owed for work already done. Whoever
     * chases that is not necessarily whoever books the job in.
     */
    public static final String WORKORDER_AUTHORIZATION_REVIEW = "supplier:workorderauth:review";

    /**
     * Running a marketing-catalogue import on demand, and reading what the catalogue last sent.
     *
     * <p>Its own permission because an import is a full read of a centrally published catalogue —
     * thousands of calls to a standards body serving every consumer at once — rather than a cheap
     * local query. Whoever may trigger that is not necessarily whoever may view a vendor profile.
     */
    public static final String MARKETING_CATALOG_IMPORT = "supplier:mktcat:import";

    private SupplierPermissions() {}
}
