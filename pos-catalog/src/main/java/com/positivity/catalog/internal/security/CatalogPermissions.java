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

    /**
     * Read product catalog data.
     *
     * <p>Replaces the dead role gate {@code hasRole('CATALOG_VIEW')}, which named a role nobody
     * has ever been granted — the endpoints it guarded were reachable only through {@code ADMIN}.
     */
    public static final String PRODUCT_VIEW = "catalog:product:view";

    /**
     * Delete a product from the catalog.
     *
     * <p>Replaces the dead role gate {@code hasRole('CATALOG_DELETE')} on product deletes; see
     * {@link #PRODUCT_VIEW} for the same problem on the read side.
     */
    public static final String PRODUCT_DELETE = "catalog:product:delete";

    /**
     * Edit product master data or product-lifecycle write actions gated alongside it.
     *
     * <p>Replaces the dead role gate {@code hasRole('CATALOG_EDIT')} on product master-data writes
     * (create/update/tracking-level) and on the lifecycle write endpoints that OR it with {@link
     * #PRODUCT_LIFECYCLE_UPDATE}; see {@link #PRODUCT_VIEW} for the same problem on the read side.
     * The permission code itself ({@code catalog:product:edit}) was already registered
     * (bit 13) but had never been wired to an enforcement site in this module.
     */
    public static final String PRODUCT_EDIT = "catalog:product:edit";

    /**
     * Read service-type catalog data.
     *
     * <p>Replaces the dead role gate {@code hasRole('CATALOG_VIEW')} on the service-type read
     * endpoints; see {@link #PRODUCT_VIEW}.
     */
    public static final String SERVICE_TYPE_VIEW = "catalog:service_type:view";

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

    /**
     * Read an item's standard-cost value and its audit history.
     *
     * <p>Distinct from {@link #ITEM_COST_UPDATE}, which is write-only and does not cover reads.
     * Replaces the dead role gate {@code hasRole('CATALOG_VIEW')} (alongside {@code MANAGER}) on
     * the item-cost read endpoints; see {@link #PRODUCT_VIEW} for the same problem elsewhere in
     * this module.
     */
    public static final String ITEM_COST_READ = "catalog:item_cost:read";

    /**
     * Create or update the location-scoped guardrail policy that bounds discount, margin and
     * auto-approval limits for location price overrides.
     *
     * <p>Replaces the dead role gate {@code hasRole('CATALOG_EDIT')}; see {@link #PRODUCT_VIEW}.
     */
    public static final String GUARDRAIL_POLICY_WRITE = "catalog:guardrail_policy:write";

    /**
     * Read a location's effective price for a product, resolved from its override records.
     *
     * <p>Replaces the dead role gate {@code hasRole('CATALOG_VIEW')}; see {@link #PRODUCT_VIEW}.
     */
    public static final String LOCATION_PRICE_OVERRIDE_READ = "catalog:location_price_override:read";

    /**
     * Create a location-specific price override for a product.
     *
     * <p>Replaces the dead role gate {@code hasRole('CATALOG_EDIT')}; see {@link #PRODUCT_VIEW}.
     */
    public static final String LOCATION_PRICE_OVERRIDE_WRITE = "catalog:location_price_override:write";

    /**
     * Read non-inventory products — items sold without stock tracking, such as fees or shop
     * supplies.
     *
     * <p>Replaces the dead role gate {@code hasRole('CATALOG_VIEW')}; see {@link #PRODUCT_VIEW}.
     */
    public static final String NON_INVENTORY_VIEW = "catalog:non_inventory:view";

    /**
     * Read substitution-group membership.
     *
     * <p>Replaces the dead role gate {@code hasRole('CATALOG_VIEW')}; see {@link #PRODUCT_VIEW}.
     */
    public static final String SUBSTITUTION_GROUP_VIEW = "catalog:substitution_group:view";

    /**
     * Create, delete or modify membership of a substitution group.
     *
     * <p>Replaces the dead role gate {@code hasRole('CATALOG_EDIT')}; see {@link #PRODUCT_VIEW}.
     */
    public static final String SUBSTITUTION_GROUP_EDIT = "catalog:substitution_group:edit";

    /**
     * Read catalog groupings.
     *
     * <p>Replaces the dead role gate {@code hasRole('CATALOG_VIEW')}; see {@link #PRODUCT_VIEW}.
     */
    public static final String CATALOG_GROUPING_VIEW = "catalog:catalog_grouping:view";

    /**
     * Create or update a catalog grouping.
     *
     * <p>Replaces the dead role gate {@code hasRole('CATALOG_EDIT')}; see {@link #PRODUCT_VIEW}.
     */
    public static final String CATALOG_GROUPING_EDIT = "catalog:catalog_grouping:edit";

    /**
     * Delete a catalog grouping.
     *
     * <p>Replaces the dead role gate {@code hasRole('CATALOG_DELETE')}; see {@link #PRODUCT_VIEW}.
     */
    public static final String CATALOG_GROUPING_DELETE = "catalog:catalog_grouping:delete";

    /**
     * Read unit-of-measure conversion factors.
     *
     * <p>Replaces the dead role gate {@code hasRole('CATALOG_VIEW')}; see {@link #PRODUCT_VIEW}.
     */
    public static final String UOM_CONVERSION_VIEW = "catalog:uom_conversion:view";

    /**
     * Create, update or deactivate a unit-of-measure conversion factor.
     *
     * <p>Replaces the dead role gate {@code hasRole('CATALOG_EDIT')}; see {@link #PRODUCT_VIEW}.
     */
    public static final String UOM_CONVERSION_EDIT = "catalog:uom_conversion:edit";

    /**
     * Read a product's alternate units of measure.
     *
     * <p>Replaces the dead role gate {@code hasRole('CATALOG_VIEW')}; see {@link #PRODUCT_VIEW}.
     */
    public static final String PRODUCT_UOM_VIEW = "catalog:product_uom:view";

    /**
     * Add, update or delete a product's alternate units of measure.
     *
     * <p>Replaces the dead role gate {@code hasRole('CATALOG_EDIT')}; see {@link #PRODUCT_VIEW}.
     */
    public static final String PRODUCT_UOM_EDIT = "catalog:product_uom:edit";

    /**
     * Read a product's tread-design match, or list products still unmatched to one.
     *
     * <p>Replaces the dead role gate {@code hasRole('CATALOG_VIEW')}; see {@link #PRODUCT_VIEW}.
     */
    public static final String TREAD_DESIGN_VIEW = "catalog:tread_design:view";

    /**
     * Read a service's labor standards — vehicle-keyed book times with provenance (#1569).
     */
    public static final String LABOR_STANDARD_VIEW = "catalog:labor_standard:view";

    /**
     * Author or supersede a DURION-source labor standard (#1569). Imported rows are not covered:
     * they are corrected by their source's next import, and this permission never lets a hand
     * edit masquerade as vendor data.
     */
    public static final String LABOR_STANDARD_MANAGE = "catalog:labor_standard:manage";

    /**
     * Re-publish catalog facts to seed or repair a downstream replica.
     *
     * <p>One code shared by all three fact-replay endpoints — product facts, service facts, and
     * supplier-article-code facts — since they are the same operational capability held by the
     * same operator; separate codes per fact type would be catalog sprawl. Replaces the dead role
     * gate {@code hasRole('CATALOG_EDIT')} on each; see {@link #PRODUCT_VIEW}.
     */
    public static final String FACT_REPLAY = "catalog:fact:replay";

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
