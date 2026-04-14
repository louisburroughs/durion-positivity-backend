package com.positivity.customer.internal.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import java.util.concurrent.TimeUnit;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Caffeine-backed cache configuration for pos-customer.
 * CAP:252 — Customer snapshot caching with 15-minute TTL.
 */
@Configuration
@EnableCaching
public class CacheConfig {

    /** Cache name used by snapshot service methods. */
    public static final String SNAPSHOT_CACHE = "customer-snapshot-v1";

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager(SNAPSHOT_CACHE);
        manager.setCaffeine(
                Caffeine.newBuilder().expireAfterWrite(15, TimeUnit.MINUTES).maximumSize(1000));
        return manager;
    }
}
