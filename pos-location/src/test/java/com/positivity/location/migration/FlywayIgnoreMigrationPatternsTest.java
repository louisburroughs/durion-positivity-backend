package com.positivity.location.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import org.flywaydb.core.api.MigrationState;
import org.flywaydb.core.api.pattern.ValidatePattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

/**
 * Pins {@code spring.flyway.ignore-migration-patterns} in {@code application.yml}.
 *
 * <p>{@code R__seed_location_2_operational_data.sql} was deleted when the alpha fixture packs
 * took over operational seeding (docs/DATA_SEED_STRATEGY.md, #1554). Every database that ever
 * ran it still carries its {@code flyway_schema_history} row, and Flyway's default validation
 * rejects that row on startup — "Detected applied migration not resolved locally: seed location
 * 2 operational data" — which aborts the whole application context. Only the property below
 * keeps those environments bootable, and nothing else in the module would notice its loss:
 * Flyway is disabled under the {@code test} profile, so no Spring-context test runs validation.
 *
 * <p>The second assertion is the reason the pattern is scoped to repeatables rather than a blanket
 * {@code *:missing}: a deleted or renamed <em>versioned</em> migration is a genuine defect and
 * must keep failing startup.
 */
class FlywayIgnoreMigrationPatternsTest {

    private static final String PROPERTY = "spring.flyway.ignore-migration-patterns";

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

    /** Reads the property exactly as it ships, then parses it the way Flyway itself does. */
    private static List<ValidatePattern> configuredPatterns() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        Properties properties = yaml.getObject();
        assertThat(properties).isNotNull();

        String raw = properties.getProperty(PROPERTY);
        assertThat(raw).as(PROPERTY).isNotBlank();

        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(pattern -> !pattern.isEmpty())
                .map(ValidatePattern::fromPattern)
                .toList();
    }
}
