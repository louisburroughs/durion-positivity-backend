package com.positivity.catalog;

import com.positivity.catalog.internal.config.JpaAuditingConfig;
import java.time.Clock;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cache.CacheManager;
import org.springframework.cache.support.NoOpCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
 * The two beans a {@code @DataJpaTest} slice of this module needs and its narrowed context does not
 * supply.
 *
 * <ul>
 *   <li>A {@link Clock}, because {@link JpaAuditingConfig} — imported here so that
 *       {@code @CreatedDate}/{@code @LastModifiedDate} populate the {@code NOT NULL} audit columns
 *       every scoped table carries — resolves its {@code DateTimeProvider} from one.
 *   <li>A {@link CacheManager}, because {@code PosCatalogApplication} is annotated
 *       {@code @EnableCaching} and the slice excludes the cache auto-configuration that would
 *       otherwise answer it. A {@link NoOpCacheManager} on purpose: a persistence slice asserts what
 *       the database returned, and a caching layer between the two could only hide a defect.
 * </ul>
 */
@TestConfiguration(proxyBeanMethods = false)
@Import(JpaAuditingConfig.class)
public class SliceSupportConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    CacheManager cacheManager() {
        return new NoOpCacheManager();
    }
}
