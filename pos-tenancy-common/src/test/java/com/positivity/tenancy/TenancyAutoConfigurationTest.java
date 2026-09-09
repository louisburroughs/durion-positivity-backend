package com.positivity.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@DisplayName("TenancyAutoConfiguration")
class TenancyAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TenancyAutoConfiguration.class))
            .withUserConfiguration(ADataSource.class);

    @Test
    @DisplayName("wraps the module's datasource by default")
    void wrapsByDefault() {
        runner.run(context -> assertThat(context.getBean(DataSource.class)).isInstanceOf(TenantAwareDataSource.class));
    }

    @Test
    @DisplayName("reads the fallback tenant from configuration")
    void readsFallback() {
        runner.withPropertyValues("pos.tenancy.fallback-tenant-id=01900000-0000-7000-8000-00000000000f")
                .run(context -> {
                    assertThat(context.getBean(TenancyProperties.class).getFallbackTenantId())
                            .hasToString("01900000-0000-7000-8000-00000000000f");
                    assertThat(context.getBean(DataSource.class)).isInstanceOf(TenantAwareDataSource.class);
                });
    }

    @Test
    @DisplayName("leaves the datasource alone when tenancy is disabled")
    void canBeDisabled() {
        runner.withPropertyValues("pos.tenancy.enabled=false")
                .run(context ->
                        assertThat(context.getBean(DataSource.class)).isNotInstanceOf(TenantAwareDataSource.class));
    }

    @Test
    @DisplayName("does not wrap a datasource that is already wrapped")
    void doesNotDoubleWrap() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(TenancyAutoConfiguration.class))
                .withUserConfiguration(AnAlreadyWrappedDataSource.class)
                .run(context -> {
                    DataSource bean = context.getBean(DataSource.class);
                    assertThat(bean).isInstanceOf(TenantAwareDataSource.class);
                    assertThat(((TenantAwareDataSource) bean).getTargetDataSource())
                            .isNotInstanceOf(TenantAwareDataSource.class);
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class ADataSource {
        @Bean
        DataSource dataSource() {
            return mock(DataSource.class);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class AnAlreadyWrappedDataSource {
        @Bean
        DataSource dataSource() {
            return new TenantAwareDataSource(mock(DataSource.class), null);
        }
    }
}
