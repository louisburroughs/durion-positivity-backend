package com.positivity.tenancy.autoconfigure;

import com.positivity.tenancy.TenantResolver;
import com.positivity.tenancy.hibernate.TenantContextIdentifierResolver;
import org.hibernate.context.spi.CurrentTenantIdentifierResolver;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;

/** Registers the {@code @TenantId} resolver with Hibernate in every JPA module. */
@AutoConfiguration(after = TenancyAutoConfiguration.class)
@ConditionalOnClass({CurrentTenantIdentifierResolver.class, HibernatePropertiesCustomizer.class})
public class TenancyHibernateAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(CurrentTenantIdentifierResolver.class)
    public TenantContextIdentifierResolver tenantContextIdentifierResolver(TenantResolver tenantResolver) {
        return new TenantContextIdentifierResolver(tenantResolver);
    }
}
