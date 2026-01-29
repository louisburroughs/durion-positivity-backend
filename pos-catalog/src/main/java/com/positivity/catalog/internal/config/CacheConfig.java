package com.positivity.catalog.internal.config;

import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.concurrent.ConcurrentMapCache;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;

/**
 * Cache configuration for product detail views.
 * Implements caching strategy per clarification resolution for Issue #16.
 * 
 * TTL Strategy:
 * - Product details: 15 seconds (based on min TTL for dynamic inventory)
 * - Pricing: 60 seconds
 * - Inventory: 15 seconds
 * - Lead time (dynamic): 5 minutes
 * - Catalog static: 24 hours
 * 
 * For V1: Using simple in-memory cache with TTL-only invalidation.
 * TODO: Add event-driven cache invalidation keys when eventing is available.
 */
@Configuration
public class CacheConfig implements CachingConfigurer {

    /**
     * Configure cache manager with product detail cache.
     * Note: SimpleCacheManager with ConcurrentMapCache doesn't support TTL natively.
     * For production, consider using Caffeine or Redis with TTL support.
     */
    @Bean
    @Override
    public CacheManager cacheManager() {
        SimpleCacheManager cacheManager = new SimpleCacheManager();
        cacheManager.setCaches(Arrays.asList(
            new ConcurrentMapCache("productDetails")
            // TODO: Replace with Caffeine cache with 15-second TTL for production
        ));
        return cacheManager;
    }
}
