package com.positivity.catalog.service;

import com.positivity.catalog.internal.dto.CatalogSearchResultDto;
import org.jspecify.annotations.NonNull;

/**
 * Public service interface for cursor-based catalog product search.
 *
 * <p>
 * This is the only externally-visible API from the catalog search feature.
 * All implementation details reside in {@code internal} packages.
 *
 * Issue: CAP-247 Story #17
 */
public interface ProductSearchService {

    /**
     * Searches catalog products using a cursor-based pagination model.
     *
     * @param q        free-text query (name, description); may be null/blank
     * @param brand    filter by manufacturer brand (exact, case-insensitive); may
     *                 be null
     * @param category filter by category name (exact, case-insensitive); may be
     *                 null
     * @param sku      filter/rank by SKU (exact, case-insensitive); may be null
     * @param cursor   opaque pagination cursor from previous response; null for
     *                 first page
     * @param limit    maximum number of results to return (1–100)
     * @return cursor-based search result
     */
    @NonNull
    CatalogSearchResultDto searchProducts(
            String q, String brand, String category, String sku, String cursor, int limit);
}
