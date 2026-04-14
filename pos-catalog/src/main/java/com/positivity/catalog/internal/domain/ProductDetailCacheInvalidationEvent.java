package com.positivity.catalog.internal.domain;

import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Event used to invalidate product detail cache entries.
 */
public record ProductDetailCacheInvalidationEvent(
        @NonNull UUID productId, @Nullable UUID locationId) {

    public ProductDetailCacheInvalidationEvent {
        Objects.requireNonNull(productId, "productId must not be null");
    }

    public static ProductDetailCacheInvalidationEvent forProduct(@NonNull UUID productId) {
        return new ProductDetailCacheInvalidationEvent(productId, null);
    }

    public static ProductDetailCacheInvalidationEvent forProductAtLocation(
            @NonNull UUID productId, @NonNull UUID locationId) {
        return new ProductDetailCacheInvalidationEvent(productId, locationId);
    }
}
