package com.positivity.tenancy;

import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Wraps the module's datasource in a {@link TenantAwareDataSource} so every connection carries the
 * bound tenant (ADR-0062 §2, plan WS1).
 *
 * <p>A {@link BeanPostProcessor} rather than a replacement bean: each module builds its own
 * datasource from its own properties, and wrapping whatever it produced leaves that alone. A static
 * post-processor is registered before any datasource is created, so it needs no ordering against
 * the JDBC auto-configuration.
 */
@AutoConfiguration
@ConditionalOnClass(DataSource.class)
@ConditionalOnProperty(prefix = "pos.tenancy", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(TenancyProperties.class)
public class TenancyAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(TenancyAutoConfiguration.class);

    @Bean
    public static BeanPostProcessor tenantAwareDataSourcePostProcessor(TenancyProperties properties) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
                if (!(bean instanceof DataSource dataSource) || bean instanceof TenantAwareDataSource) {
                    return bean;
                }
                if (properties.getFallbackTenantId() == null) {
                    log.info(
                            "Tenancy binding active on datasource '{}' with no fallback tenant: work that binds no "
                                    + "tenant will read zero rows and cannot insert (ADR-0062 fail-closed).",
                            beanName);
                } else {
                    log.warn(
                            "Tenancy binding active on datasource '{}' with transitional fallback tenant {}. "
                                    + "Remove pos.tenancy.fallback-tenant-id once the access token carries tid (plan WS2b).",
                            beanName,
                            properties.getFallbackTenantId());
                }
                return new TenantAwareDataSource(dataSource, properties.getFallbackTenantId());
            }
        };
    }
}
