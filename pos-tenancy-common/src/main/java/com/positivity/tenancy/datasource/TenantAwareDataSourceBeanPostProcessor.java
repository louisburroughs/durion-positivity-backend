package com.positivity.tenancy.datasource;

import com.positivity.tenancy.TenantResolver;
import java.util.Locale;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;

/**
 * Wraps every {@link DataSource} bean in a {@link TenantAwareDataSource}. Flyway's own datasource
 * (a bean whose name mentions {@code flyway}, or the one Boot derives from {@code spring.flyway.user})
 * is left alone: migrations run on the owner credential with no tenant bound, and the seed scripts
 * bind their own tenant per transaction.
 */
public class TenantAwareDataSourceBeanPostProcessor implements BeanPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(TenantAwareDataSourceBeanPostProcessor.class);

    private final ObjectProvider<TenantResolver> tenantResolver;

    public TenantAwareDataSourceBeanPostProcessor(ObjectProvider<TenantResolver> tenantResolver) {
        this.tenantResolver = tenantResolver;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof DataSource dataSource
                && !(bean instanceof TenantAwareDataSource)
                && !beanName.toLowerCase(Locale.ROOT).contains("flyway")) {
            log.info("Binding app.current_tenant per checkout on DataSource bean '{}'", beanName);
            return new TenantAwareDataSource(dataSource, tenantResolver.getObject());
        }
        return bean;
    }
}
