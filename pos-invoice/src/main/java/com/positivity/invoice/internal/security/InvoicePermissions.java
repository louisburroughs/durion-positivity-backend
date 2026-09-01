package com.positivity.invoice.internal.security;

/**
 * Permission names this module enforces, as constants rather than string literals at each call
 * site.
 *
 * <h2>Why constants and not literals</h2>
 *
 * A literal is invisible to a reader looking for everywhere a permission is used, and it is one
 * typo away from an authority nobody holds — {@code @PreAuthorize} fails closed, so a misspelling
 * does not break the build or the test suite, it silently locks the endpoint. Naming the permission
 * once means the compiler checks every use of it.
 *
 * <p>The repo-wide permission tooling reads these too:
 * {@code scripts/generate-permissions.sh --sync} resolves constant references when it decides
 * whether a permission is registered in the catalogs, so a permission introduced here is picked up
 * without a manual bit assignment.
 */
public final class InvoicePermissions {
    /** Manage invoice billing rules and defaults. */
    public static final String BILLING_RULES = "invoice:billing-rules";

    /** Finalize a draft invoice; Service Advisor limited to ≤$500 without manager approval, Shop Manager unlimited (Story #13, AC3). */
    public static final String FINALIZE = "invoice:finalize";

    /** Manage invoice lifecycle operations. */
    public static final String MANAGE = "invoice:manage";

    /**
     * Read invoices, invoice items, deposit credits and refunds (#1612).
     *
     * <p>Every read route used to demand {@link #MANAGE}, so seven roles that legitimately needed
     * to look at an invoice were offered write authority or nothing. A role holding {@code MANAGE}
     * does not implicitly hold this: the guards name one permission each, and the roles that write
     * invoices are granted both.
     */
    public static final String VIEW = "invoice:invoice:view";

    private InvoicePermissions() {
        // Utility class - prevent instantiation
    }
}
