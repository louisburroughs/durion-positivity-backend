package com.positivity.tenancy.autoconfigure;

import com.positivity.tenancy.StaticTenantRegistry;
import com.positivity.tenancy.TenancyProperties;
import com.positivity.tenancy.TenantContextTaskDecorator;
import com.positivity.tenancy.TenantIterator;
import com.positivity.tenancy.TenantKeyGenerator;
import com.positivity.tenancy.TenantRegistry;
import com.positivity.tenancy.TenantResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.context.annotation.Bean;

/** Core tenancy beans: properties, resolver, registry, iterator, cache keys, executor propagation. */
@AutoConfiguration
@EnableConfigurationProperties(TenancyProperties.class)
public class TenancyAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(TenancyAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    public TenantResolver tenantResolver(TenancyProperties properties) {
        properties
                .getDefaultTenantId()
                .ifPresentOrElse(
                        id -> log.warn(
                                "pos.tenancy.default-tenant-id={} is set: unbound work runs as that tenant"
                                        + " (transitional, ADR-0062 section 9)",
                                id),
                        () -> log.info("Tenancy is strict: unbound work carries no tenant and RLS fails closed"));
        return new TenantResolver(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public TenantRegistry tenantRegistry(TenancyProperties properties) {
        return new StaticTenantRegistry(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public TenantIterator tenantIterator(TenantRegistry registry) {
        return new TenantIterator(registry);
    }

    @Bean(TenantKeyGenerator.BEAN_NAME)
    @ConditionalOnMissingBean(name = TenantKeyGenerator.BEAN_NAME)
    public TenantKeyGenerator tenantKeyGenerator(TenantResolver tenantResolver) {
        return new TenantKeyGenerator(tenantResolver);
    }

    /** Makes {@link TenantKeyGenerator} the default for every {@code @Cacheable} that names no generator. */
    @Bean
    @ConditionalOnMissingBean(CachingConfigurer.class)
    public CachingConfigurer tenantCachingConfigurer(TenantKeyGenerator tenantKeyGenerator) {
        return new CachingConfigurer() {
            @Override
            public KeyGenerator keyGenerator() {
                return tenantKeyGenerator;
            }
        };
    }

    @Bean
    @ConditionalOnMissingBean
    public TenantContextTaskDecorator tenantContextTaskDecorator() {
        return new TenantContextTaskDecorator();
    }
}
