package com.positivity.accounting.internal.config;

import jakarta.persistence.EntityManagerFactory;
import org.hibernate.dialect.Dialect;
import org.hibernate.dialect.PostgreSQLDialect;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Runtime answer to "is the active database PostgreSQL?", resolved once from
 * the Hibernate dialect the {@link EntityManagerFactory} was built with.
 *
 * <p>The module runs on PostgreSQL/TimescaleDB in every deployed profile but on
 * H2 in the dev profile and in the Spring Boot slice/contract tests. A handful
 * of native queries are PostgreSQL-only ({@code generate_series}, regex
 * {@code substring}, {@code ::bigint} casts) and fail with an H2 parse error
 * rather than degrading, so their callers need a portable way to ask the
 * question instead of relying on javadoc that nothing enforces.
 *
 * @author Louis Burroughs
 * @since 2026-08-14
 */
@Component
public class DatabaseDialectSupport {

    private static final Logger log = LoggerFactory.getLogger(DatabaseDialectSupport.class);

    private final boolean postgreSql;

    public DatabaseDialectSupport(EntityManagerFactory entityManagerFactory) {
        Dialect dialect = entityManagerFactory
                .unwrap(SessionFactoryImplementor.class)
                .getJdbcServices()
                .getDialect();
        this.postgreSql = dialect instanceof PostgreSQLDialect;
        log.info(
                "Resolved Hibernate dialect {}; PostgreSQL-only queries are {}",
                dialect.getClass().getName(),
                postgreSql ? "enabled" : "disabled");
    }

    /**
     * Whether the active Hibernate dialect is PostgreSQL (which includes the
     * TimescaleDB deployments, since they use {@link PostgreSQLDialect}).
     *
     * @return true when PostgreSQL-only SQL may be executed
     */
    public boolean isPostgreSql() {
        return postgreSql;
    }
}
