package com.positivity.inventory.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Runs {@code V42__putaway_rule_match_criteria.sql} verbatim against an in-memory H2 in PostgreSQL
 * mode (issue #1514).
 *
 * <p>Three things need proving and nothing else in the module proves any of them. First, the
 * migration has to parse on H2, because the {@code dev} profile runs H2 and the Postgres-dump
 * {@code (col)::text = ANY ((ARRAY[...])::text[])} CHECK idiom that
 * {@code V1__baseline_inventory_schema.sql:63} carries is a syntax error there. Second, the data
 * migration has to translate pre-#1514 rows to {@code ANY} rather than leave them NULL against a
 * NOT NULL column — the whole migration fails if it does not, and the seeded rule is the one row
 * every environment has. Third, the CHECK constraints have to actually reject the two silent
 * misconfigurations they exist for: the entity's {@code @Enumerated} only guards writes that go
 * through Hibernate, and Flyway is disabled under the {@code test} profile, so no Spring-context
 * test exercises the database-level guard.
 */
class PutawayRuleMatchCriteriaMigrationTest {

    private static final String MIGRATION = "/db/migration/V42__putaway_rule_match_criteria.sql";

    /** Column subset of {@code V1__baseline_inventory_schema.sql:63} plus V30's strategy column. */
    private static final String BASELINE_TABLE = """
            CREATE TABLE putaway_rule (
                rule_id uuid NOT NULL,
                priority integer NOT NULL,
                criteria text,
                destination_location_id uuid NOT NULL,
                destination_strategy varchar(32) DEFAULT 'FIXED' NOT NULL,
                is_enabled boolean NOT NULL,
                created_at timestamp(6) with time zone NOT NULL,
                updated_at timestamp(6) with time zone NOT NULL
            )
            """;

    private Connection connection;

    @AfterEach
    void tearDown() throws SQLException {
        connection.close();
    }

    @BeforeEach
    void createBaseline() throws SQLException {
        connection = DriverManager.getConnection(
                "jdbc:h2:mem:v42_" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        MigrationScripts.execute(connection, BASELINE_TABLE);
    }

    /** Applies V42 after seeding whatever pre-migration rows a test wants. */
    private void applyMigration() throws IOException, SQLException {
        for (String statement : MigrationScripts.statements(MIGRATION)) {
            MigrationScripts.execute(connection, statement);
        }
    }

    private void insertPreMigrationRule(UUID ruleId, String criteria) throws SQLException {
        MigrationScripts.execute(
                connection,
                "INSERT INTO putaway_rule (rule_id, priority, criteria, destination_location_id, is_enabled,"
                        + " created_at, updated_at) VALUES ('" + ruleId + "', 1, "
                        + (criteria == null ? "NULL" : "'" + criteria + "'") + ", '"
                        + UUID.randomUUID() + "', TRUE, NOW(), NOW())");
    }

    private void insertPostMigrationRule(String matchType, String matchValue) throws SQLException {
        MigrationScripts.execute(
                connection,
                "INSERT INTO putaway_rule (rule_id, priority, match_type, match_value, destination_location_id,"
                        + " is_enabled, created_at, updated_at) VALUES ('" + UUID.randomUUID() + "', 1, "
                        + (matchType == null ? "NULL" : "'" + matchType + "'") + ", "
                        + (matchValue == null ? "NULL" : "'" + matchValue + "'") + ", '"
                        + UUID.randomUUID() + "', TRUE, NOW(), NOW())");
    }

    @Test
    @DisplayName("#1514 - the migration parses and applies on H2 in PostgreSQL mode")
    void appliesOnH2() throws Exception {
        applyMigration();

        assertThat(MigrationScripts.count(connection, "putaway_rule", "1 = 1")).isZero();
    }

    @Test
    @DisplayName("#1514 - the dead criteria column is gone, not deprecated in place")
    void dropsTheCriteriaColumn() throws Exception {
        applyMigration();

        assertThatThrownBy(() -> MigrationScripts.count(connection, "putaway_rule", "criteria IS NULL"))
                .isInstanceOf(SQLException.class);
    }

    @Test
    @DisplayName("#1514 - the seeded OIL- rule becomes an ANY rule, which is what it already behaved as")
    void migratesTheSeededRuleToAny() throws Exception {
        UUID seededRuleId = UUID.fromString("6f46541c-937d-397a-076f-63e092cabed6");
        insertPreMigrationRule(seededRuleId, "{\"sku_prefix\":\"OIL-\"}");

        applyMigration();

        assertThat(MigrationScripts.count(
                        connection,
                        "putaway_rule",
                        "rule_id = '" + seededRuleId + "' AND match_type = 'ANY' AND match_value IS NULL"))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("#1514 - every pre-migration row is translated, so the NOT NULL column can be enforced")
    void migratesEveryExistingRow() throws Exception {
        insertPreMigrationRule(UUID.randomUUID(), "{\"sku_prefix\":\"OIL-\"}");
        insertPreMigrationRule(UUID.randomUUID(), null);
        insertPreMigrationRule(UUID.randomUUID(), "{}");

        applyMigration();

        assertThat(MigrationScripts.count(connection, "putaway_rule", "match_type = 'ANY'"))
                .isEqualTo(3);
    }

    @Test
    @DisplayName("#1514 - match_type is NOT NULL after the migration")
    void matchTypeIsNotNull() throws Exception {
        applyMigration();

        assertThatThrownBy(() -> insertPostMigrationRule(null, null)).isInstanceOf(SQLException.class);
    }

    @Test
    @DisplayName("#1514 - every declared match type is accepted")
    void acceptsEveryDeclaredMatchType() throws Exception {
        applyMigration();

        for (String matchType : List.of("SKU", "SUBCATEGORY", "CATEGORY")) {
            insertPostMigrationRule(matchType, UUID.randomUUID().toString());
        }
        insertPostMigrationRule("ANY", null);

        assertThat(MigrationScripts.count(connection, "putaway_rule", "1 = 1")).isEqualTo(4);
    }

    @Test
    @DisplayName("#1514 - the CHECK constraint rejects a match type outside the enum")
    void rejectsUnknownMatchType() throws Exception {
        applyMigration();

        assertThatThrownBy(() -> insertPostMigrationRule("SKU_PREFIX", "OIL-"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("PUTAWAY_RULE_MATCH_TYPE_CHECK");
    }

    @Test
    @DisplayName("#1514 - a typed rule with no match value is refused: it would match nothing")
    void rejectsTypedRuleWithoutMatchValue() throws Exception {
        applyMigration();

        assertThatThrownBy(() -> insertPostMigrationRule("CATEGORY", null))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("PUTAWAY_RULE_MATCH_VALUE_CHECK");
    }

    @Test
    @DisplayName("#1514 - an ANY rule carrying a match value is refused: the matcher would ignore it")
    void rejectsAnyRuleWithMatchValue() throws Exception {
        applyMigration();

        assertThatThrownBy(
                        () -> insertPostMigrationRule("ANY", UUID.randomUUID().toString()))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("PUTAWAY_RULE_MATCH_VALUE_CHECK");
    }
}
