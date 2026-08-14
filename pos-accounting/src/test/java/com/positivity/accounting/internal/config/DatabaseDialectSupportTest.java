package com.positivity.accounting.internal.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.persistence.EntityManagerFactory;
import org.hibernate.dialect.Dialect;
import org.hibernate.dialect.H2Dialect;
import org.hibernate.dialect.PostgreSQLDialect;
import org.hibernate.engine.jdbc.spi.JdbcServices;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link DatabaseDialectSupport} (issue #1244): PostgreSQL-only
 * SQL must be gated on the dialect the EntityManagerFactory was actually built
 * with, not on the deployment profile a caller assumes is active.
 */
class DatabaseDialectSupportTest {

    @Test
    @DisplayName("Reports PostgreSQL when the session factory was built with the PostgreSQL dialect")
    void detectsPostgreSql() {
        assertThat(new DatabaseDialectSupport(entityManagerFactoryWith(new PostgreSQLDialect())).isPostgreSql())
                .isTrue();
    }

    @Test
    @DisplayName("Reports non-PostgreSQL for the H2 dev/test dialect")
    void detectsH2AsNonPostgreSql() {
        assertThat(new DatabaseDialectSupport(entityManagerFactoryWith(new H2Dialect())).isPostgreSql())
                .isFalse();
    }

    private static EntityManagerFactory entityManagerFactoryWith(Dialect dialect) {
        JdbcServices jdbcServices = mock(JdbcServices.class);
        when(jdbcServices.getDialect()).thenReturn(dialect);

        SessionFactoryImplementor sessionFactory = mock(SessionFactoryImplementor.class);
        when(sessionFactory.getJdbcServices()).thenReturn(jdbcServices);

        EntityManagerFactory entityManagerFactory = mock(EntityManagerFactory.class);
        when(entityManagerFactory.unwrap(SessionFactoryImplementor.class)).thenReturn(sessionFactory);
        return entityManagerFactory;
    }
}
