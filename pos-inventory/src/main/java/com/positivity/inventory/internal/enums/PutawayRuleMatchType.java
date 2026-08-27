package com.positivity.inventory.internal.enums;

import java.util.List;

/**
 * What a {@code putaway_rule} matches a received line against (issue #1514).
 *
 * <p>Replaces the dead {@code criteria} JSON column. Rules are resolved <em>per line item</em> in
 * the strict precedence {@link #SKU} &gt; {@link #SUBCATEGORY} &gt; {@link #CATEGORY} &gt;
 * {@link #ANY}, with the lowest {@code priority} winning inside a tier.
 *
 * <p>{@link #SUBCATEGORY} has to outrank {@link #CATEGORY} rather than the other way round because
 * {@code Batteries} is a subcategory of {@code Electrical System}: a category-only key cannot
 * express the hazard containment the narrower level carries, so the narrower level must be able to
 * override the broader one.
 */
public enum PutawayRuleMatchType {

    /** {@code matchValue} is a catalog product id — the most specific rule there is. */
    SKU,

    /** {@code matchValue} is a catalog subcategory id. */
    SUBCATEGORY,

    /** {@code matchValue} is a catalog category id. */
    CATEGORY,

    /**
     * Matches every line. {@code matchValue} must be null. An enabled {@code ANY} rule is the
     * terminal fallback that replaced the hardcoded default location, and is what guarantees a
     * receipt for a brand-new uncategorised SKU never dead-ends.
     */
    ANY;

    /**
     * The tiers in the order the matcher must try them. Declared explicitly rather than derived
     * from {@link #ordinal()} so that reordering the enum constants — a refactor that looks
     * cosmetic — cannot silently change which rule wins.
     */
    private static final List<PutawayRuleMatchType> PRECEDENCE = List.of(SKU, SUBCATEGORY, CATEGORY, ANY);

    /** Most specific first. */
    public static List<PutawayRuleMatchType> precedence() {
        return PRECEDENCE;
    }

    /** Whether this tier carries a {@code matchValue}; only {@link #ANY} does not. */
    public boolean requiresMatchValue() {
        return this != ANY;
    }
}
