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
    /**
     * View invoice analytics reports (revenue-by-customer, invoicing lag; #1589, #1592). Shared
     * by both Wave 2 analytics endpoints rather than split per-report: they are the same
     * business capability (reporting visibility into invoicing performance) and a role that
     * needs one legitimately needs the other.
     */
    public static final String ANALYTICS_VIEW = "invoice:analytics:view";

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

    /**
     * Void an authorized (not yet captured) payment hold (#2226, BILL-DEC-008). Replaces the
     * unregistered {@code VOID_PAYMENT} raw string that only pos-order's service-to-service header
     * could ever satisfy.
     */
    public static final String PAYMENT_VOID = "invoice:payment:void";

    /**
     * Refund a captured payment (#2226, BILL-DEC-008). Replaces the unregistered
     * {@code REFUND_PAYMENT} raw string.
     */
    public static final String PAYMENT_REFUND = "invoice:payment:refund";

    /**
     * Elevation that bypasses the 24-hour void window and the 180-day refund window in
     * {@code PaymentReversalServiceImpl} (#2226, BILL-DEC-010). Replaces the unregistered
     * {@code SUPERVISOR_OVERRIDE} raw string used there. Do not confuse with {@link #FINALIZE}
     * elevation, which is a distinct capability ({@code invoice:finalize:override}).
     */
    public static final String PAYMENT_OVERRIDE = "invoice:payment:override";

    /**
     * Generate a receipt for an invoice payment (#2226, BILL-DEC-008). Replaces the unregistered
     * {@code GENERATE_RECEIPT} raw string.
     */
    public static final String RECEIPT_GENERATE = "invoice:receipt:generate";

    /**
     * Elevation that bypasses the 5-reprint cap on an existing receipt (#2226, BILL-DEC-010).
     * Replaces the unregistered {@code SUPERVISOR_OVERRIDE} raw string used in
     * {@code ReceiptServiceImpl}. Distinct from {@link #PAYMENT_OVERRIDE}: reprinting has no money
     * movement of its own, so the two overrides are granted independently.
     */
    public static final String RECEIPT_REPRINT_OVERRIDE = "invoice:receipt:reprint_override";

    /**
     * Issue a manual (out-of-band) refund not anchored to a captured payment intent — against an
     * invoice or directly against a customer party (#2226, BILL-DEC-008). Replaces the
     * unregistered {@code ISSUE_MANUAL_REFUND} raw string.
     */
    public static final String REFUND_ISSUE_MANUAL = "invoice:refund:issue_manual";

    private InvoicePermissions() {
        // Utility class - prevent instantiation
    }
}
