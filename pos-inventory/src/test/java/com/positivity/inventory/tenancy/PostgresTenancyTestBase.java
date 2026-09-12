package com.positivity.inventory.tenancy;

import com.positivity.inventory.InventoryPostgresContainer;
import javax.sql.DataSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Full-context tenancy tests against the shared {@link InventoryPostgresContainer}, laid out the
 * way Compose and the alpha host are (ADR-0062 §3, layer 2): the container's superuser owns the
 * schema and runs Flyway, the application pool connects as the shared non-owner {@code pos_app}
 * role. The runtime is strict ({@code pg} profile): nothing binds a tenant unless the test does.
 *
 * <p>Requires Docker.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("pg")
public abstract class PostgresTenancyTestBase {

    static final String APP_ROLE = InventoryPostgresContainer.APP_ROLE;

    /** The container superuser: owns every table, runs Flyway, and bypasses RLS like the alpha owner does. */
    static DataSource ownerDataSource() {
        return InventoryPostgresContainer.ownerDataSource();
    }

    /** Runs once the container is up and before the Spring context starts, so the role exists for Flyway and the pool. */
    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        InventoryPostgresContainer.registerDataSourceProperties(registry);
    }
}
