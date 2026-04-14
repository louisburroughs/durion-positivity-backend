package com.positivity.catalog.internal.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
 * For V1: Using Caffeine in-memory cache with 15-second TTL plus
 * event-driven key eviction for product detail updates.
 */
@Configuration
public class CacheConfig implements CachingConfigurer {

    /**
     * Configure cache manager with product detail cache.
     */
    @Bean
    @Override
    public CacheManager cacheManager() {
        CaffeineCacheManager cacheManager = new CaffeineCacheManager("productDetails");
        cacheManager.setCaffeine(Caffeine.newBuilder().expireAfterWrite(Duration.ofSeconds(15)));
        return cacheManager;
    }
}
