package com.positivity.catalog.internal.security;

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
public final class CatalogPermissions {

    // ── Product and catalog data ────────────────────────────────────────────────────────

    /** Create a product in the catalog. */
    public static final String PRODUCT_CREATE = "catalog:product:create";

    /** Read the manufacturer's suggested retail price carried on a catalog product. */
    public static final String MSRP_READ = "catalog:msrp:read";

    /** Set the manufacturer's suggested retail price on a catalog product. */
    public static final String MSRP_WRITE = "catalog:msrp:write";

    /** Read price-book entries owned by this module. */
    public static final String PRICE_BOOK_READ = "catalog:price_book:read";

    /** Write price-book entries owned by this module. */
    public static final String PRICE_BOOK_WRITE = "catalog:price_book:write";

    /**
     * Read supplier cost as carried on catalog products.
     *
     * <p>Distinct from the sell-side price permissions above: supplier cost is what the vendor
     * charges us and participates in no sell-price resolution (ADR-0053 §4, ADR-0054).
     */
    public static final String SUPPLIER_COST_READ = "catalog:supplier_cost:read";

    /**
     * Set an item's standard cost.
     *
     * <p>Replaces the authority {@code inventory.cost.standard.update}, which was written with dots
     * instead of colons and so matched no permission the catalog has ever held. It could be granted
     * to nobody, which made the endpoint's third authorisation branch dead: it was reachable only by
     * the {@code ADMIN} and {@code MANAGER} roles beside it, and the intent to gate it by permission
     * silently did nothing. The name is a catalog one because the endpoint is here — the old string
     * claimed an inventory permission that no module has ever defined.
     */
    public static final String ITEM_COST_UPDATE = "catalog:item_cost:update";

    // ── Permissions owned by other domains ──────────────────────────────────────────────
    //
    // Declared here so this module's call sites are constants like every other, but the names
    // belong to pos-price and the product domain. Their definition, bit assignment and
    // description live with their owner — this is a reference, not a claim of ownership.

    /** Approve a price override; owned by the pricing domain. */
    public static final String PRICING_OVERRIDE_APPROVE = "pricing:override:approve";

    /** Change a product's lifecycle state; owned by the product domain. */
    public static final String PRODUCT_LIFECYCLE_UPDATE = "product:lifecycle:update";

    private CatalogPermissions() {
        // Utility class - prevent instantiation
    }
}
