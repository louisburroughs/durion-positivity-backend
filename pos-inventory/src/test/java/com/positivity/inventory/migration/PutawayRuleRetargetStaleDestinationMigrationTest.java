package com.positivity.inventory.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Runs {@code V46__retarget_stale_putaway_rule_destination.sql} verbatim against an in-memory H2 in
 * PostgreSQL mode (issue #1543).
 *
 * <p>The point of V46 is that the correction the repeatable seed could not deliver — its
 * {@code ON CONFLICT (rule_id) DO NOTHING} never touches an existing row — reaches every
 * already-seeded database. So beyond parsing on the engine the {@code dev} profile runs, the tests
 * pin the migration's scoping: every rule carrying the known-bad destination is retargeted whatever
 * its id or tier (operator copies included), and any other destination is left alone.
 */
class PutawayRuleRetargetStaleDestinationMigrationTest {

    private static final String MIGRATION = "/db/migration/V46__retarget_stale_putaway_rule_destination.sql";

    /** The destination that was never a storage_location row; see the migration header. */
    private static final String STALE_DESTINATION = "96dd346a-047c-86f5-3c9a-7c8cac53da86";

    /** 'Main Parts Shelf' — the destination the since-retired Flyway seed carried (issue #1554). */
    private static final String CORRECTED_DESTINATION = "01960004-0001-7000-8000-000000000003";

    private Connection connection;

    @BeforeEach
    void openConnection() throws Exception {
        connection = DriverManager.getConnection(
                "jdbc:h2:mem:v46_" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        createPutawayRuleTable();
    }

    @AfterEach
    void tearDown() throws SQLException {
        connection.close();
    }

    /** The columns V46 touches, in the shape V42/V44 leave them; same reduction as the V44 test. */
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
                    enabled_any_guard character varying(3),
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

    private void insertRule(String ruleId, String matchType, String destination) throws SQLException {
        MigrationScripts.execute(
                connection,
                String.format(
                        "INSERT INTO putaway_rule (rule_id, priority, match_type, match_value,"
                                + " destination_location_id, destination_strategy, is_enabled, created_at,"
                                + " updated_at) VALUES ('%s', 1, '%s', NULL, '%s', 'FIXED', TRUE,"
                                + " CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                        ruleId, matchType, destination));
    }

    private static String rule(int n) {
        return String.format("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f5a%02d", n);
    }

    @Test
    @DisplayName("#1543 - the migration parses and applies on H2 in PostgreSQL mode")
    void appliesOnH2() throws Exception {
        applyMigration();

        assertThat(MigrationScripts.count(connection, "putaway_rule", "1 = 1")).isZero();
    }

    @Test
    @DisplayName("#1543 - the seeded ANY rule still carrying the stale destination is retargeted")
    void retargetsTheStaleSeededRule() throws Exception {
        insertRule("6f46541c-937d-397a-076f-63e092cabed6", "ANY", STALE_DESTINATION);

        applyMigration();

        assertThat(MigrationScripts.count(
                        connection,
                        "putaway_rule",
                        "rule_id = '6f46541c-937d-397a-076f-63e092cabed6'" + " AND destination_location_id = '"
                                + CORRECTED_DESTINATION + "'"))
                .isEqualTo(1);
        assertThat(MigrationScripts.count(
                        connection, "putaway_rule", "destination_location_id = '" + STALE_DESTINATION + "'"))
                .isZero();
    }

    @Test
    @DisplayName("#1543 - scoping is by the bad value, so an operator-authored copy is repaired too")
    void retargetsOperatorCopiesOfTheStaleDestination() throws Exception {
        insertRule(rule(1), "CATEGORY", STALE_DESTINATION);
        insertRule(rule(2), "SKU", STALE_DESTINATION);

        applyMigration();

        assertThat(MigrationScripts.count(
                        connection, "putaway_rule", "destination_location_id = '" + CORRECTED_DESTINATION + "'"))
                .isEqualTo(2);
        assertThat(MigrationScripts.count(
                        connection, "putaway_rule", "destination_location_id = '" + STALE_DESTINATION + "'"))
                .isZero();
    }

    @Test
    @DisplayName("#1543 - any other destination is legitimate configuration and is left untouched")
    void leavesOtherDestinationsAlone() throws Exception {
        String operatorBin = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f5aff";
        insertRule(rule(1), "CATEGORY", operatorBin);
        insertRule(rule(2), "ANY", CORRECTED_DESTINATION);

        applyMigration();

        assertThat(MigrationScripts.count(
                        connection,
                        "putaway_rule",
                        "rule_id = '" + rule(1) + "' AND destination_location_id = '" + operatorBin + "'"))
                .isEqualTo(1);
        assertThat(MigrationScripts.count(
                        connection,
                        "putaway_rule",
                        "rule_id = '" + rule(2) + "' AND destination_location_id = '" + CORRECTED_DESTINATION + "'"))
                .isEqualTo(1);
    }
}
