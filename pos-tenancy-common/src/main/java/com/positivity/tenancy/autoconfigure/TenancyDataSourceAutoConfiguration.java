package com.positivity.tenancy.autoconfigure;

import com.positivity.tenancy.TenantResolver;
import com.positivity.tenancy.datasource.TenantAwareDataSourceBeanPostProcessor;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;

/**
 * Wraps the module's {@code DataSource} so every checkout binds {@code app.current_tenant}. Opt out
 * with {@code pos.tenancy.datasource.enabled=false} (only sensible for a module with no scoped
 * tables).
 */
@AutoConfiguration(after = TenancyAutoConfiguration.class)
@ConditionalOnClass(DataSource.class)
@ConditionalOnBooleanProperty(name = "pos.tenancy.datasource.enabled", matchIfMissing = true)
public class TenancyDataSourceAutoConfiguration {

    @Bean
    public static TenantAwareDataSourceBeanPostProcessor tenantAwareDataSourceBeanPostProcessor(
            ObjectProvider<TenantResolver> tenantResolver) {
        return new TenantAwareDataSourceBeanPostProcessor(tenantResolver);
    }
}
