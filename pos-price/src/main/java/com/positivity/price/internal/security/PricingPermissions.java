package com.positivity.price.internal.security;

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
public final class PricingPermissions {

    // ── Base prices ─────────────────────────────────────────────────────────────────────

    /** Create a base price. */
    public static final String BASE_PRICE_CREATE = "pricing:base_price:create";

    // ── Promotions ──────────────────────────────────────────────────────────────────────

    /** View promotions and their eligibility rules. */
    public static final String PROMOTION_VIEW = "pricing:promotion:view";

    /** Create, change and retire promotions. */
    public static final String PROMOTION_MANAGE = "pricing:promotion:manage";

    /**
     * Apply a promotion to a quote or order.
     *
     * <p>Separate from {@link #PROMOTION_MANAGE}: deciding what a promotion is and deciding that
     * one applies to a customer in front of you are different powers, and a counter operator
     * normally holds only the second.
     */
    public static final String PROMOTION_APPLY = "pricing:promotion:apply";

    // ── Restrictions ────────────────────────────────────────────────────────────────────

    /** Define and change pricing restrictions. */
    public static final String RESTRICTION_MANAGE = "pricing:restriction:manage";

    /**
     * Override a pricing restriction on a specific transaction.
     *
     * <p>The most consequential permission here: its holder sets aside a rule the business
     * deliberately configured, for one sale, and the money difference is real.
     */
    public static final String RESTRICTION_OVERRIDE = "pricing:restriction:override";

    /** View restriction rules by id or in the active rule listing. */
    public static final String RULE_VIEW = "pricing:rule:view";

    /** Evaluate products and context against the active restriction rules. */
    public static final String RESTRICTIONS_VIEW = "pricing:restrictions:view";

    // ── Normalization ───────────────────────────────────────────────────────────────────

    /** Edit price normalization rules. */
    public static final String NORMALIZATION_EDIT = "pricing:normalization:edit";

    // ── Labor rates (#1575 Tier 0) ──────────────────────────────────────────────────────

    /** Author shop labor rates and the labor matrix. */
    public static final String LABOR_RATE_MANAGE = "pricing:labor_rate:manage";

    /** View stored labor rates and matrix steps. */
    public static final String LABOR_RATE_VIEW = "pricing:labor_rate:view";

    /**
     * Resolve the rate applicable to one job, through the scoped service-to-service edge.
     *
     * <p>Separate from {@link #LABOR_RATE_VIEW}: listing what a shop charges and pricing a
     * specific job are different powers, and the edge's holder is a service account, not a
     * person browsing the rate table.
     */
    public static final String LABOR_RATE_QUOTE = "pricing:labor_rate:quote";

    private PricingPermissions() {
        // Utility class - prevent instantiation
    }
}
