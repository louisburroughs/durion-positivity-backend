package com.positivity.order.tenancy;

import com.positivity.order.OrderPostgresContainer;
import javax.sql.DataSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The tenancy tests' view of the shared {@link OrderPostgresContainer}: the container's superuser
 * owns the schema and runs Flyway; the application pool connects as the shared non-owner
 * {@code pos_app} role (ADR-0062 §3, layer 2). The runtime is strict ({@code pg} profile): nothing
 * binds a tenant unless the test does.
 *
 * <p>Requires Docker.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("pg")
public abstract class PostgresTenancyTestBase {

    static final String APP_ROLE = OrderPostgresContainer.APP_ROLE;

    /** The container superuser: owns every table, runs Flyway, and bypasses RLS like the alpha owner does. */
    static DataSource ownerDataSource() {
        return OrderPostgresContainer.ownerDataSource();
    }

    /** Runs once the container is up and before the Spring context starts, so the role exists for Flyway and the pool. */
    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        OrderPostgresContainer.registerDataSourceProperties(registry);
    }
}
