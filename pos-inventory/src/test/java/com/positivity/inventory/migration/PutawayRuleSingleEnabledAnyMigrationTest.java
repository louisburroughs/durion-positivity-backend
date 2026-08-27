package com.positivity.inventory.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Runs {@code V44__putaway_rule_single_enabled_any.sql} verbatim against an in-memory H2 in
 * PostgreSQL mode (issue #1514).
 *
 * <p>The point of V44 is that the single-enabled-{@code ANY} invariant survives concurrency, which
 * the service's read-before-write check cannot guarantee on its own. A partial unique index would be
 * the natural expression but H2 has none, so the migration uses a nullable guard column under a
 * plain UNIQUE constraint. That trick is only correct if the engine really does permit unlimited
 * nulls in a unique column — which is exactly what these tests pin, on the engine the {@code dev}
 * profile actually runs.
 */
class PutawayRuleSingleEnabledAnyMigrationTest {

    private static final String BASELINE = "/db/migration/V1__baseline_inventory_schema.sql";
    private static final String MATCH_CRITERIA = "/db/migration/V42__putaway_rule_match_criteria.sql";
    private static final String MIGRATION = "/db/migration/V44__putaway_rule_single_enabled_any.sql";

    private Connection connection;

    @BeforeEach
    void openConnection() throws Exception {
        connection = DriverManager.getConnection(
                "jdbc:h2:mem:v44_" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        createPutawayRuleTable();
    }

    @AfterEach
    void tearDown() throws SQLException {
        connection.close();
    }

    /**
     * The columns V44 depends on, in the shape V42 leaves them. The full baseline is not replayed:
     * it carries the whole inventory schema and a Postgres-dump CHECK idiom H2 rejects, and none of
     * that bears on this constraint.
     *
     * @see #BASELINE
     * @see #MATCH_CRITERIA
     */
    private void createPutawayRuleTable() throws SQLException {
        MigrationScripts.execute(connection, """
                CREATE TABLE putaway_rule (
                    rule_id uuid NOT NULL PRIMARY KEY,
                    priority integer NOT NULL,
                    match_type character varying(20) NOT NULL,
                    match_value character varying(128),
                    destination_location_id uuid NOT NULL,
                    destination_strategy character varying(32) NOT NULL DEFAULT 'FIXED',
                    is_enabled boolean NOT NULL,
                    created_at timestamp(6) with time zone NOT NULL,
                    updated_at timestamp(6) with time zone NOT NULL
                )
                """);
    }

    private void applyMigration() throws Exception {
        for (String statement : MigrationScripts.statements(MIGRATION)) {
            MigrationScripts.execute(connection, statement);
        }
    }

    private void insertRule(String ruleId, String matchType, int priority, boolean enabled) throws SQLException {
        MigrationScripts.execute(
                connection,
                String.format(
                        "INSERT INTO putaway_rule (rule_id, priority, match_type, match_value,"
                                + " destination_location_id, destination_strategy, is_enabled, created_at,"
                                + " updated_at) VALUES ('%s', %d, '%s', NULL,"
                                + " '01960004-0001-7000-8000-000000000003', 'FIXED', %s,"
                                + " CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                        ruleId, priority, matchType, enabled));
    }

    private void insertGuarded(String ruleId, String guard) throws SQLException {
        MigrationScripts.execute(
                connection,
                String.format(
                        "INSERT INTO putaway_rule (rule_id, priority, match_type, match_value,"
                                + " destination_location_id, destination_strategy, is_enabled, enabled_any_guard,"
                                + " created_at, updated_at) VALUES ('%s', 1, 'ANY', NULL,"
                                + " '01960004-0001-7000-8000-000000000003', 'FIXED', TRUE, %s,"
                                + " CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                        ruleId, guard == null ? "NULL" : "'" + guard + "'"));
    }

    private static String rule(int n) {
        return String.format("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f5a%02d", n);
    }

    @Test
    @DisplayName("#1514 - the migration parses and applies on H2 in PostgreSQL mode")
    void appliesOnH2() throws Exception {
        applyMigration();

        assertThat(MigrationScripts.count(connection, "putaway_rule", "1 = 1")).isZero();
    }

    @Test
    @DisplayName("#1514 - a second enabled ANY rule is refused by the database, not just by the service")
    void refusesASecondEnabledAnyRuleAtTheDatabase() throws Exception {
        applyMigration();
        insertGuarded(rule(1), "ANY");

        assertThatThrownBy(() -> insertGuarded(rule(2), "ANY")).isInstanceOf(SQLException.class);
    }

    @Test
    @DisplayName("#1514 - the guard permits unlimited nulls, so non-ANY and disabled rules are unconstrained")
    void permitsUnlimitedNulls() throws Exception {
        applyMigration();

        insertGuarded(rule(1), null);
        insertGuarded(rule(2), null);
        insertGuarded(rule(3), null);

        assertThat(MigrationScripts.count(connection, "putaway_rule", "enabled_any_guard IS NULL"))
                .isEqualTo(3);
    }

    @Test
    @DisplayName("#1514 - backfill keeps the rule the matcher would reach and disables the rest")
    void backfillKeepsTheReachableRuleAndDisablesTheOthers() throws Exception {
        // Three enabled ANY rules already exist, which the pre-V44 race could produce. Only the
        // lowest-priority one was ever reachable, so only it may keep running.
        insertRule(rule(3), "ANY", 30, true);
        insertRule(rule(1), "ANY", 10, true);
        insertRule(rule(2), "ANY", 20, true);
        insertRule(rule(4), "CATEGORY", 5, true);

        applyMigration();

        assertThat(MigrationScripts.count(connection, "putaway_rule", "enabled_any_guard = 'ANY'"))
                .isEqualTo(1);
        assertThat(MigrationScripts.count(
                        connection, "putaway_rule", "rule_id = '" + rule(1) + "' AND is_enabled = TRUE"))
                .isEqualTo(1);
        assertThat(MigrationScripts.count(connection, "putaway_rule", "match_type = 'ANY' AND is_enabled = FALSE"))
                .isEqualTo(2);
        // A non-ANY rule is untouched: the backfill must not disable ordinary configuration.
        assertThat(MigrationScripts.count(
                        connection, "putaway_rule", "rule_id = '" + rule(4) + "' AND is_enabled = TRUE"))
                .isEqualTo(1);
    }
}
