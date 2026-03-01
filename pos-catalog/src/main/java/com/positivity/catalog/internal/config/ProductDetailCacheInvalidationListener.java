package com.positivity.catalog.internal.config;

import com.positivity.catalog.internal.domain.ProductDetailCacheInvalidationEvent;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Listens for product cache invalidation events and evicts impacted keys.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProductDetailCacheInvalidationListener {

    private static final String PRODUCT_DETAILS_CACHE = "productDetails";

    private final CacheManager cacheManager;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onProductDetailCacheInvalidation(@NonNull ProductDetailCacheInvalidationEvent event) {
        Cache cache = cacheManager.getCache(PRODUCT_DETAILS_CACHE);
        if (cache == null) {
            log.warn("Cache '{}' not configured; skipping invalidation", PRODUCT_DETAILS_CACHE);
            return;
        }

        if (event.locationId() != null) {
            String cacheKey = buildCacheKey(event.productId(), event.locationId());
            cache.evictIfPresent(cacheKey);
            log.debug("Evicted cache key={} from cache={}", cacheKey, PRODUCT_DETAILS_CACHE);
            return;
        }

        if (cache instanceof CaffeineCache caffeineCache) {
            String keyPrefix = event.productId() + "-";
            int evictedCount = 0;
            for (Object key : caffeineCache.getNativeCache().asMap().keySet()) {
                if (key instanceof String keyValue && keyValue.startsWith(keyPrefix)) {
                    caffeineCache.evictIfPresent(keyValue);
                    evictedCount++;
                }
            }
            log.debug("Evicted {} cache entries for productId={} from cache={}",
                    evictedCount, event.productId(), PRODUCT_DETAILS_CACHE);
            return;
        }

        cache.clear();
        log.debug("Fallback cache clear for cache={} after product-level invalidation", PRODUCT_DETAILS_CACHE);
    }

    private @NonNull String buildCacheKey(@NonNull UUID productId, @NonNull UUID locationId) {
        return productId + "-" + locationId;
    }
}
