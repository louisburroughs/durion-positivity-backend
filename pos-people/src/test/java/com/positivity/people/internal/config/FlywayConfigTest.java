package com.positivity.people.internal.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.Arrays;
import java.util.List;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

/**
 * The mcpFlyway bean is hand-built (Boot's Flyway auto-configuration backs off), so
 * spring.flyway.* properties reach the instance only through explicit binding in
 * FlywayConfig. These tests pin that binding for ignore-migration-patterns — the
 * property that exempts the retired timekeeping seed's schema-history row (#1527);
 * losing it makes pos-people fail startup validation in every environment that ever
 * ran the seed. Flyway.configure().load() does not touch the DataSource, so a mock
 * suffices.
 */
@SuppressWarnings("java:S100")
class FlywayConfigTest {

    private final FlywayConfig config = new FlywayConfig();

    @Test
    void mcpFlyway_appliesIgnoreMigrationPatterns() {
        Flyway flyway = config.mcpFlyway(mock(DataSource.class), new String[] {"repeatable:missing"});

        // Assert presence rather than total count so Flyway-supplied default patterns
        // (present or future) don't break the test; the paired default-config check
        // proves the pattern really came from the binding.
        assertThat(patternStrings(flyway))
                .anySatisfy(pattern -> assertThat(pattern).contains("repeatable"));
        assertThat(patternStrings(flywayWithDefaults()))
                .noneSatisfy(pattern -> assertThat(pattern).contains("repeatable"));
    }

    @Test
    void mcpFlyway_emptyPatternBinding_leavesFlywayDefaults() {
        // @Value("${...:}") yields a single blank entry when the property is unset;
        // it must not be passed to Flyway as a pattern.
        Flyway defaultFlyway = config.mcpFlyway(mock(DataSource.class), new String[] {""});

        assertThat(patternStrings(defaultFlyway)).isEqualTo(patternStrings(flywayWithDefaults()));
    }

    private static Flyway flywayWithDefaults() {
        return Flyway.configure().dataSource(mock(DataSource.class)).load();
    }

    private static List<String> patternStrings(Flyway flyway) {
        return Arrays.stream(flyway.getConfiguration().getIgnoreMigrationPatterns())
                .map(Object::toString)
                .toList();
    }
}
