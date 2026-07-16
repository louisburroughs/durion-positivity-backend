package com.positivity.warranty.internal.client;

import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * Read-only client for pos-catalog product lookups (manufacturer + warranty terms).
 *
 * <p>Reads degrade to {@link Optional#empty()} on 404 or transport failure.
 */
public interface CatalogClient {

    /**
     * Fetch product info by product id ({@code GET /v1/products/{productId}}).
     */
    @NonNull
    Optional<ProductInfo> getProduct(@NonNull UUID productId);

    /** Product fields warranty claims need, mirrored from catalog's ProductDto. */
    record ProductInfo(
            UUID id,
            String sku,
            String name,
            UUID manufacturerId,
            String manufacturerName,
            String manufacturerBrand,
            UUID categoryId,
            String category,
            String warranty,
            String manufacturerWarranty) {}
}
