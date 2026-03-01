package com.positivity.catalog.internal.service;

import com.positivity.catalog.internal.domain.ProductDetailCacheInvalidationEvent;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * Publishes cache invalidation events for product detail entries.
 */
@Service
@RequiredArgsConstructor
public class ProductDetailCacheInvalidationPublisher {

    private final ApplicationEventPublisher applicationEventPublisher;

    public void invalidateProduct(@NonNull UUID productId) {
        applicationEventPublisher.publishEvent(ProductDetailCacheInvalidationEvent.forProduct(productId));
    }

    public void invalidateProductAtLocation(@NonNull UUID productId, @NonNull UUID locationId) {
        applicationEventPublisher.publishEvent(
                ProductDetailCacheInvalidationEvent.forProductAtLocation(productId, locationId));
    }
}
