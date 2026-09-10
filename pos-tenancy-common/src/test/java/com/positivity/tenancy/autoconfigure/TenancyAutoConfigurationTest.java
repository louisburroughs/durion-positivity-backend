package com.positivity.tenancy.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.tenancy.TenantContextTaskDecorator;
import com.positivity.tenancy.TenantIterator;
import com.positivity.tenancy.TenantKeyGenerator;
import com.positivity.tenancy.TenantRegistry;
import com.positivity.tenancy.TenantResolver;
import com.positivity.tenancy.datasource.TenantAwareDataSource;
import com.positivity.tenancy.hibernate.TenantContextIdentifierResolver;
import com.positivity.tenancy.kafka.TenantRecordInterceptor;
import com.positivity.tenancy.web.TenantContextFilter;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class TenancyAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    TenancyAutoConfiguration.class,
                    TenancyDataSourceAutoConfiguration.class,
                    TenancyHibernateAutoConfiguration.class,
                    TenancyKafkaAutoConfiguration.class));

    @Configuration(proxyBeanMethods = false)
    static class DataSources {
        @Bean
        DataSource dataSource() {
            return new DriverManagerDataSource("jdbc:h2:mem:tenancy;DB_CLOSE_DELAY=-1", "sa", "");
        }

        @Bean
        DataSource flywayDataSource() {
            return new DriverManagerDataSource("jdbc:h2:mem:tenancy;DB_CLOSE_DELAY=-1", "sa", "");
        }
    }

    @Test
    void registersTheRuntimeAndWrapsTheApplicationDataSource() {
        runner.withUserConfiguration(DataSources.class)
                .withPropertyValues("pos.tenancy.default-tenant-id=01900000-0000-7000-8000-000000000001")
                .run(context -> {
                    assertThat(context).hasSingleBean(TenantResolver.class);
                    assertThat(context).hasSingleBean(TenantRegistry.class);
                    assertThat(context).hasSingleBean(TenantIterator.class);
                    assertThat(context).hasSingleBean(TenantKeyGenerator.class);
                    assertThat(context.getBean(org.springframework.cache.annotation.CachingConfigurer.class)
                                    .keyGenerator())
                            .isSameAs(context.getBean(TenantKeyGenerator.class));
                    assertThat(context).hasSingleBean(TenantContextTaskDecorator.class);
                    assertThat(context).hasSingleBean(TenantContextIdentifierResolver.class);
                    assertThat(context).hasSingleBean(TenantRecordInterceptor.class);
                    assertThat(context.getBean("dataSource")).isInstanceOf(TenantAwareDataSource.class);
                    assertThat(context.getBean("flywayDataSource")).isNotInstanceOf(TenantAwareDataSource.class);
                    assertThat(context.getBean(TenantRegistry.class).activeTenantIds())
                            .containsExactly(UUID.fromString("01900000-0000-7000-8000-000000000001"));
                    assertThat(context.getBean(TenantResolver.class).hasDefault())
                            .isTrue();
                });
    }

    @Test
    void servletModulesGetTheRequestFilterAheadOfSecurity() {
        new WebApplicationContextRunner()
                .withConfiguration(
                        AutoConfigurations.of(TenancyAutoConfiguration.class, TenancyWebAutoConfiguration.class))
                .run(context -> {
                    assertThat(context).hasSingleBean(TenantContextFilter.class);
                    assertThat(context.getBean("tenantContextFilterRegistration"))
                            .hasFieldOrPropertyWithValue("order", TenancyWebAutoConfiguration.FILTER_ORDER);
                });
        runner.run(context -> assertThat(context).doesNotHaveBean(TenantContextFilter.class));
    }

    @Test
    void datasourceWrappingCanBeDisabled() {
        runner.withUserConfiguration(DataSources.class)
                .withPropertyValues("pos.tenancy.datasource.enabled=false")
                .run(context -> {
                    assertThat(context.getBean("dataSource")).isNotInstanceOf(TenantAwareDataSource.class);
                    assertThat(context.getBean(TenantResolver.class).hasDefault())
                            .isFalse();
                });
    }
}
