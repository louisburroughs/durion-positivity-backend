package com.positivity.catalog.internal.service;

import com.positivity.catalog.internal.entity.Category;
import com.positivity.catalog.internal.entity.Subcategory;
import com.positivity.catalog.internal.exception.CatalogValidationException;
import com.positivity.catalog.internal.repository.CategoryRepository;
import com.positivity.catalog.internal.repository.SubcategoryRepository;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves catalog category and subcategory <em>names</em> to their identifiers, for ingest paths that carry
 * human-authored names rather than ids (the bulk-ingest CSV pack).
 *
 * <p><strong>Matching rule:</strong> the supplied name is trimmed and matched case-insensitively against the
 * Flyway-seeded reference data ({@code R__seed_reference_catalog.sql} — 12 categories, 40 subcategories).
 * Names arrive from hand-maintained CSV fixtures where trailing whitespace and inconsistent casing are
 * ordinary editing accidents, not meaningful distinctions, and the seeded names are unique under
 * case-folding, so tolerating both cannot introduce an ambiguity that exact matching would have avoided.
 *
 * <p><strong>Unknown names fail; they never land uncategorized.</strong> Categories are curated reference
 * data (Tier 1 Flyway seed per {@code docs/DATA_SEED_STRATEGY.md}), so an unrecognized name is a defect in
 * the caller's data, not a request to invent a category — and creating one on the fly would let a typo
 * silently fork the taxonomy. Landing the row uncategorized instead would be worse still: it looks like
 * success while producing exactly the inert category-based putaway that issue #1514 exists to fix, because
 * an uncategorized product matches no category rule. Callers therefore get a {@link
 * CatalogValidationException}, which the bulk-ingest path reports as a per-row {@code CATALOG_INGEST_FAILED}
 * verdict (the rest of the batch still proceeds) and the single-product API reports as HTTP 400.
 *
 * <p>An <em>absent</em> name (null or blank) is different from an unknown one: it means "not classified" and
 * resolves to {@code null}, leaving the product uncategorized without error.
 *
 * <p>Ambiguous names also fail. Neither {@code category.name} nor {@code subcategory.name} has a unique
 * constraint, so duplicates are possible in principle; guessing which one the caller meant would silently
 * bind products to an arbitrary taxonomy node.
 */
@Service
@RequiredArgsConstructor
public class CategoryNameResolver {

    private final CategoryRepository categoryRepository;
    private final SubcategoryRepository subcategoryRepository;

    /**
     * Resolves a category name to its id.
     *
     * @param name the category name; null or blank yields {@code null} (product stays uncategorized)
     * @return the seeded category id, or {@code null} when no name was supplied
     * @throws CatalogValidationException when the name is supplied but unknown or ambiguous
     */
    @Transactional(readOnly = true)
    public @Nullable UUID resolveCategoryId(@Nullable String name) {
        return resolve(name, "Category", categoryRepository::findByNameIgnoreCase, Category::getId);
    }

    /**
     * Resolves a subcategory name to its id.
     *
     * @param name the subcategory name; null or blank yields {@code null} (product stays unclassified)
     * @return the seeded subcategory id, or {@code null} when no name was supplied
     * @throws CatalogValidationException when the name is supplied but unknown or ambiguous
     */
    @Transactional(readOnly = true)
    public @Nullable UUID resolveSubcategoryId(@Nullable String name) {
        return resolve(name, "Subcategory", subcategoryRepository::findByNameIgnoreCase, Subcategory::getId);
    }

    private <T> @Nullable UUID resolve(
            @Nullable String name, String label, Function<String, List<T>> finder, Function<T, UUID> idExtractor) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String normalized = name.trim();
        List<T> matches = finder.apply(normalized);
        if (matches.isEmpty()) {
            throw new CatalogValidationException(
                    label + " not found by name: " + normalized + " (must match seeded catalog reference data)");
        }
        if (matches.size() > 1) {
            throw new CatalogValidationException(
                    label + " name is ambiguous: " + normalized + " matches " + matches.size() + " records");
        }
        return idExtractor.apply(matches.get(0));
    }
}
