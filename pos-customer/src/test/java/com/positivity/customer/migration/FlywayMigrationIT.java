package com.positivity.customer.migration;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.customer.CustomerPostgresContainer;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Boots the full application context against a real Postgres (Testcontainers) so that the entire
 * Flyway migration chain runs, then Hibernate validates the entity mappings (ddl-auto=validate).
 * Context startup succeeding proves the migrations and the JPA entities agree.
 *
 * <p>Added for issue #684: the three rollout breakages (seed comma, two {@code primary} field
 * mismatches) were untested SQL because the default tests use H2 with Flyway disabled. This test
 * exercises the real DDL in CI. Requires Docker.
 *
 * <p>The module carries no repeatable seed any more. #1968 retired
 * {@code R__seed_customer_operational_data.sql} because its demo rows are test data that must not
 * reach a production database, so {@code db/migration} holds only {@code V1__baseline_customer.sql}
 * and {@code V2__event_outbox_tenant_id.sql}. This IT therefore covers migration and schema state
 * only; seeded content now arrives through the bulk-load pipeline
 * ({@code scripts/fixtures/seed/alpha/}) and is not this test's subject.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("pg")
class FlywayMigrationIT {

    /**
     * Connected as the schema owner rather than as {@code pos_app}: this test reads the system
     * catalog, which the application role cannot do unscoped under row-level security.
     */
    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        CustomerPostgresContainer.registerOwnerDataSourceProperties(registry);
    }

    @Autowired
    private DataSource dataSource;

    /**
     * Context loads only if every migration applied cleanly and the schema validated against the
     * entities. We additionally assert the thin-link drops from issue #684 actually took effect.
     */
    @Test
    void migrationsApply_andThinLinkDropsTookEffect() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        // 2c.3: contact_point table dropped.
        Integer contactPointTables = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.tables "
                        + "WHERE table_schema = 'public' AND table_name = 'contact_point'",
                Integer.class);
        assertThat(contactPointTables).isZero();

        // Step 4: person_party name columns dropped.
        Integer nameColumns = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = 'person_party' "
                        + "AND column_name IN ('first_name', 'last_name')",
                Integer.class);
        assertThat(nameColumns).isZero();

        // A person_party row count used to sit here, counting the seventy demo persons the retired
        // seed inserted. See the class javadoc for why there is nothing to count any more.

        // FI-4 (#1135): structured-address replica columns and the org-address replica table.
        Integer addressColumns = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = 'ext_people_contact_person' "
                        + "AND column_name IN ('address_line1', 'address_region', 'address_country_code')",
                Integer.class);
        assertThat(addressColumns).isEqualTo(3);
        Integer orgAddressTables = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.tables "
                        + "WHERE table_schema = 'public' AND table_name = 'ext_organization_postal_address'",
                Integer.class);
        assertThat(orgAddressTables).isEqualTo(1);
    }
}
