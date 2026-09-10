package com.positivity.tenancy.datasource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.positivity.tenancy.TenancyProperties;
import com.positivity.tenancy.TenantResolver;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class TenantAwareDataSourceBeanPostProcessorTest {

    @SuppressWarnings("unchecked")
    private final ObjectProvider<TenantResolver> resolver = mock(ObjectProvider.class);

    private final TenantAwareDataSourceBeanPostProcessor processor =
            new TenantAwareDataSourceBeanPostProcessor(resolver);

    @Test
    void wrapsTheApplicationDataSourceOnly() {
        org.mockito.Mockito.when(resolver.getObject()).thenReturn(new TenantResolver(new TenancyProperties()));
        DataSource app = mock(DataSource.class);
        DataSource flyway = mock(DataSource.class);

        assertThat(processor.postProcessAfterInitialization(app, "dataSource"))
                .isInstanceOf(TenantAwareDataSource.class);
        assertThat(processor.postProcessAfterInitialization(flyway, "flywayDataSource"))
                .isSameAs(flyway);
        assertThat(processor.postProcessAfterInitialization("not a datasource", "other"))
                .isEqualTo("not a datasource");
    }

    @Test
    void doesNotWrapTwice() {
        org.mockito.Mockito.when(resolver.getObject()).thenReturn(new TenantResolver(new TenancyProperties()));
        TenantAwareDataSource already = new TenantAwareDataSource(mock(DataSource.class), resolver.getObject());

        assertThat(processor.postProcessAfterInitialization(already, "dataSource"))
                .isSameAs(already);
    }
}
