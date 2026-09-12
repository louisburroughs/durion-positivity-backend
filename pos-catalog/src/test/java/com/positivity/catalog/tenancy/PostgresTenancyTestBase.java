package com.positivity.catalog.tenancy;

import com.positivity.catalog.CatalogPostgresContainer;
import javax.sql.DataSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Full-context tenancy tests against the shared {@link CatalogPostgresContainer}, laid out the way
 * Compose and the alpha host are (ADR-0062 §3, layer 2): the container's superuser owns the schema
 * and runs Flyway; the application pool connects as the shared non-owner {@code pos_app} role. The
 * runtime is strict ({@code pg} profile): nothing binds a tenant unless the test does.
 *
 * <p>Requires Docker.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("pg")
public abstract class PostgresTenancyTestBase {

    /** The non-owner role the application pool connects as; it holds no BYPASSRLS. */
    static final String APP_ROLE = CatalogPostgresContainer.APP_ROLE;

    /** The container superuser: owns every table, runs Flyway, and bypasses RLS like the alpha owner does. */
    static DataSource ownerDataSource() {
        return CatalogPostgresContainer.ownerDataSource();
    }

    /** Runs once the container is up and before the Spring context starts, so the role exists for Flyway and the pool. */
    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        CatalogPostgresContainer.registerDataSourceProperties(registry);
    }
}
