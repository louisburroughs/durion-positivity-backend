package com.positivity.image.internal.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

/**
 * The Flyway bean is hand-built (Boot's auto-configuration backs off), so the ADR-0062 credential
 * split reaches Flyway only through {@link FlywayConfig}: the owner credential when
 * {@code spring.flyway.user} is set, the application pool otherwise. {@code load()} does not open a
 * connection, so an H2 URL and a mock {@link DataSource} suffice.
 */
class FlywayConfigTest {

    private static final String[] LOCATIONS = {"classpath:db/migration"};
    private static final String APP_URL = "jdbc:h2:mem:image-app";
    private static final String OWNER_URL = "jdbc:h2:mem:image-owner";

    private final FlywayConfig config = new FlywayConfig();

    @Test
    void mcpFlyway_ownerCredentialSet_migratesOnItsOwnConnection() {
        Flyway flyway = config.mcpFlyway(mock(DataSource.class), OWNER_URL, "owner", "secret", APP_URL, LOCATIONS);

        assertThat(flyway.getConfiguration().getUrl()).isEqualTo(OWNER_URL);
        assertThat(flyway.getConfiguration().getUser()).isEqualTo("owner");
        assertThat(flyway.getConfiguration().getLocations())
                .extracting(Object::toString)
                .containsExactly(LOCATIONS);
    }

    @Test
    void mcpFlyway_ownerCredentialWithoutUrl_fallsBackToTheDatasourceUrl() {
        Flyway flyway = config.mcpFlyway(mock(DataSource.class), "", "owner", "secret", APP_URL, LOCATIONS);

        assertThat(flyway.getConfiguration().getUrl()).isEqualTo(APP_URL);
        assertThat(flyway.getConfiguration().getUser()).isEqualTo("owner");
    }

    @Test
    void mcpFlyway_noOwnerCredential_migratesOnTheApplicationPool() {
        DataSource pool = mock(DataSource.class);

        Flyway flyway = config.mcpFlyway(pool, "", "", "", APP_URL, LOCATIONS);

        assertThat(flyway.getConfiguration().getDataSource()).isSameAs(pool);
    }

    @Test
    void mcpFlyway_nullOwnerCredential_migratesOnTheApplicationPool() {
        DataSource pool = mock(DataSource.class);

        Flyway flyway = config.mcpFlyway(pool, null, null, null, APP_URL, LOCATIONS);

        assertThat(flyway.getConfiguration().getDataSource()).isSameAs(pool);
    }
}
