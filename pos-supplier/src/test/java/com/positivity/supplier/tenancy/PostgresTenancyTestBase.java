package com.positivity.supplier.tenancy;

import com.positivity.supplier.SupplierPostgresContainer;
import javax.sql.DataSource;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Full-context tests against the shared {@link SupplierPostgresContainer}: the real Flyway chain,
 * Hibernate {@code validate}, and the pool connected as the non-owner {@code pos_app} role exactly
 * as alpha is. The runtime is strict ({@code pg} profile): nothing binds a tenant unless the test
 * does. The profile carries a fixed test key for {@code AuditPayloadCipher}, which accepts an
 * ephemeral key only when every active profile is dev or test.
 *
 * <p>Requires Docker.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("pg")
@ResourceLock(SupplierPostgresContainer.RESOURCE_LOCK)
public abstract class PostgresTenancyTestBase {

    static final String APP_ROLE = SupplierPostgresContainer.APP_ROLE;

    /** The container superuser: owns every table, runs Flyway, and bypasses RLS like the alpha owner does. */
    static DataSource ownerDataSource() {
        return SupplierPostgresContainer.ownerDataSource();
    }

    /** Runs once the container is up and before the Spring context starts, so the role exists for Flyway and the pool. */
    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        SupplierPostgresContainer.registerDataSourceProperties(registry);
    }
}
