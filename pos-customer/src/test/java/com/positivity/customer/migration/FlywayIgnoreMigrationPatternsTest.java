package com.positivity.customer.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.positivity.customer.internal.config.FlywayConfig;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationState;
import org.flywaydb.core.api.pattern.ValidatePattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

/**
 * Pins {@code spring.flyway.ignore-migration-patterns} in {@code application.yml} <em>and</em> the
 * code path that hands it to Flyway.
 *
 * <p>R__seed_customer_operational_data.sql was deleted when the alpha fixture packs took over
 * operational seeding (docs/DATA_SEED_STRATEGY.md, #1968). Every database that ever ran it still
 * carries its {@code flyway_schema_history} row, and Flyway's default validation rejects that row
 * on startup -- "Detected applied migration not resolved locally" -- which aborts the whole
 * application context. This is not hypothetical: it took the alpha deployment down on
 * {@code a3c64e609}.
 *
 * <p>Two assertions are needed rather than one, because this module does not use Boot's Flyway
 * auto-configuration. {@link FlywayConfig} builds the {@code Flyway} bean itself, so a module can
 * carry the property in its yaml and still ignore it entirely -- which is exactly the state every
 * one of these modules was in. The yaml assertions prove the value is configured; the bean
 * assertion proves it is actually applied.
 *
 * <p>The versioned-migration assertion is why the pattern is scoped to repeatables rather than a
 * blanket {@code *:missing}: a deleted or renamed <em>versioned</em> migration is a genuine defect
 * and must keep failing startup.
 */
class FlywayIgnoreMigrationPatternsTest {

    private static final String PROPERTY = "spring.flyway.ignore-migration-patterns";
    private static final String[] LOCATIONS = {"classpath:db/migration"};

    @Test
    @DisplayName("the configured patterns exempt a missing repeatable migration")
    void patterns_exemptMissingRepeatable() {
        assertThat(configuredPatterns())
                .anyMatch(pattern -> pattern.matchesMigration(false, MigrationState.MISSING_SUCCESS));
    }

    @Test
    @DisplayName("the configured patterns still fail a missing versioned migration")
    void patterns_doNotExemptMissingVersioned() {
        assertThat(configuredPatterns())
                .noneMatch(pattern -> pattern.matchesMigration(true, MigrationState.MISSING_SUCCESS));
    }

    @Test
    @DisplayName("the Flyway bean applies the configured patterns")
    void flywayBean_appliesConfiguredPatterns() {
        String[] patterns = configuredPatternValues();
        assertThat(patterns).as("%s must be set in application.yml", PROPERTY).isNotEmpty();

        DataSource dataSource = mock(DataSource.class);
        Flyway flyway = new FlywayConfig().mcpFlyway(dataSource, "", "", "", "jdbc:postgresql://localhost/x", patterns);

        assertThat(flyway.getConfiguration().getIgnoreMigrationPatterns())
                .as("the bean must pass %s through to Flyway", PROPERTY)
                .anyMatch(pattern -> pattern.matchesMigration(false, MigrationState.MISSING_SUCCESS));
    }

    @Test
    @DisplayName("the Flyway bean leaves validation alone when no patterns are configured")
    void flywayBean_appliesNothingWhenUnset() {
        DataSource dataSource = mock(DataSource.class);
        String[] patterns = new String[0];
        Flyway flyway = new FlywayConfig().mcpFlyway(dataSource, "", "", "", "jdbc:postgresql://localhost/x", patterns);

        assertThat(flyway.getConfiguration().getIgnoreMigrationPatterns())
                .noneMatch(pattern -> pattern.matchesMigration(false, MigrationState.MISSING_SUCCESS));
    }

    private static String[] configuredPatternValues() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        Properties properties = yaml.getObject();
        String raw = properties == null ? null : properties.getProperty(PROPERTY);
        if (raw == null || raw.isBlank()) {
            return new String[0];
        }
        return Arrays.stream(raw.split(",")).map(String::trim).toArray(String[]::new);
    }

    private static List<ValidatePattern> configuredPatterns() {
        String[] values = configuredPatternValues();
        assertThat(values).as("%s must be set in application.yml", PROPERTY).isNotEmpty();
        return Arrays.stream(values).map(ValidatePattern::fromPattern).toList();
    }
}
