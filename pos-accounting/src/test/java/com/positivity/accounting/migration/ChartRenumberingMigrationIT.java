package com.positivity.accounting.migration;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.accounting.AccountingPostgresContainer;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/**
 * V11 and the repeatable seed on a database that ran V2 and the pre-#2511 seed (#2511 AC 1, AC 14; AW30): the
 * renumbered accounts keep their ids, their Labor &amp; Overhead lines carry the new codes, 6340 is renamed,
 * no tenant row is deleted, the template moves to the new codes, and a second seed run changes nothing.
 */
@DisplayName("V11: the AW30 renumbering and the S15 template (#2511, real Postgres)")
class ChartRenumberingMigrationIT {

    private static final UUID DEFAULT_TENANT = UUID.fromString("01900000-0000-7000-8000-000000000001");
    private static final UUID PLATFORM = UUID.fromString("01900000-0000-7000-8000-000000000000");
    private static final Map<String, String> RENUMBERED =
            Map.of("6010", "6100", "6015", "6102", "6025", "6105", "6115", "6040", "6900", "4940");

    @Test
    @DisplayName("AC14: renumbered under the same ids, lines follow, 6340 renamed, nothing deleted, seed idempotent")
    void renumbersADatabaseThatRanTheOldSeeds() throws SQLException {
        DataSource database = AccountingPostgresContainer.ownerDataSource("chart-renumbering");
        JdbcTemplate jdbc = new JdbcTemplate(database);
        // The database as the last release left it: V1-V10 and the pre-#2511 template seed.
        Flyway.configure()
                .dataSource(database)
                .locations(com.positivity.accounting.AccountingMigrations.releasedUpTo(10))
                .load()
                .migrate();
        // The default tenant as S37's applier left it: V2's chart plus 6115 Cash Short, its CASH_SHORT mapping and
        // the entries the applier recorded.
        UUID cashShort = UUID.fromString("01999999-2511-7000-8000-000000006115");
        jdbc.update(
                "INSERT INTO gl_account (tenant_id, gl_account_id, account_code, account_name, account_type,"
                        + " account_subtype, reconcilable, activation_date, version, created_at, created_by,"
                        + " modified_at, modified_by) VALUES (?, ?, '6115', 'Cash Short', 'EXPENSE', 'OPERATING_EXPENSE',"
                        + " false, TIMESTAMP '2020-01-01 00:00:00', 0, TIMESTAMPTZ '2026-10-05 00:00:00+00',"
                        + " 'tenant-template', TIMESTAMPTZ '2026-10-05 00:00:00+00', 'tenant-template')",
                DEFAULT_TENANT,
                cashShort);
        jdbc.update(
                "INSERT INTO gl_mapping (tenant_id, gl_mapping_id, source_system, external_code, gl_account_id,"
                        + " effective_start_date, created_at, created_by) VALUES (?, ?, 'ACCOUNTING',"
                        + " 'REGISTER_OVER_SHORT_CASH_SHORT', ?, TIMESTAMP '2020-01-01 00:00:00',"
                        + " TIMESTAMPTZ '2026-10-05 00:00:00+00', 'tenant-template')",
                DEFAULT_TENANT,
                UUID.fromString("01999999-2511-7000-8000-00000000c115"),
                cashShort);
        for (String key : List.of("ACCOUNT:6115", "ACCOUNT:6010", "STATEMENT_LINE:LABOR_OVERHEAD:6010")) {
            jdbc.update(
                    "INSERT INTO accounting_template_entry (tenant_id, entry_id, entry_key, kind, outcome,"
                            + " entry_fingerprint, template_value, created_at, modified_at) VALUES (?, ?, ?, ?,"
                            + " 'ADOPTED', 'f', 'v', TIMESTAMPTZ '2026-10-05 00:00:00+00',"
                            + " TIMESTAMPTZ '2026-10-05 00:00:00+00')",
                    DEFAULT_TENANT,
                    UUID.randomUUID(),
                    key,
                    key.startsWith("ACCOUNT") ? "ACCOUNT" : "STATEMENT_LINE");
        }
        Map<String, UUID> before = idsByCode(jdbc, DEFAULT_TENANT);
        int defaultRowsBefore = count(jdbc, "gl_account", DEFAULT_TENANT);
        int defaultLinesBefore = count(jdbc, "statement_line_mappings", DEFAULT_TENANT);
        assertThat(before).containsKeys("6010", "6015", "6025", "6115", "6900", "6340");

        Flyway.configure()
                .dataSource(database)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        Map<String, UUID> after = idsByCode(jdbc, DEFAULT_TENANT);
        RENUMBERED.forEach((oldCode, newCode) -> assertThat(after.get(newCode))
                .as("%s reads %s under the same gl_account_id", oldCode, newCode)
                .isEqualTo(before.get(oldCode)));
        assertThat(after).doesNotContainKeys("6010", "6015", "6025", "6115", "6900");
        assertThat(count(jdbc, "gl_account", DEFAULT_TENANT))
                .as("no account deleted")
                .isEqualTo(defaultRowsBefore);
        assertThat(count(jdbc, "statement_line_mappings", DEFAULT_TENANT))
                .as("no line deleted")
                .isEqualTo(defaultLinesBefore);
        assertThat(jdbc.queryForList(
                        "SELECT s.account_name FROM statement_line_mappings s JOIN gl_account g ON g.tenant_id ="
                                + " s.tenant_id AND g.gl_account_id = s.gl_account_id WHERE s.tenant_id = ? AND"
                                + " s.statement_type = 'LABOR_OVERHEAD' AND g.account_code IN ('6100', '6102', '6105',"
                                + " '4940') ORDER BY 1",
                        String.class,
                        DEFAULT_TENANT))
                .as("the Labor & Overhead lines carry the new codes")
                .containsExactly("4940", "6100", "6102", "6105");
        assertThat(jdbc.queryForObject(
                        "SELECT account_name FROM gl_account WHERE tenant_id = ? AND account_code = '6340'",
                        String.class,
                        DEFAULT_TENANT))
                .isEqualTo("Shop Supplies & Consumables");
        assertThat(jdbc.queryForObject(
                        "SELECT account_type FROM gl_account WHERE tenant_id = ? AND account_code = '4940'",
                        String.class,
                        DEFAULT_TENANT))
                .isEqualTo("REVENUE");
        assertThat(jdbc.queryForObject(
                        "SELECT g.account_code FROM gl_mapping m JOIN gl_account g ON g.tenant_id = m.tenant_id AND"
                                + " g.gl_account_id = m.gl_account_id WHERE m.tenant_id = ? AND m.external_code ="
                                + " 'REGISTER_OVER_SHORT_CASH_SHORT'",
                        String.class,
                        DEFAULT_TENANT))
                .as("REGISTER_OVER_SHORT / CASH_SHORT resolves 6040")
                .isEqualTo("6040");
        assertThat(jdbc.queryForList(
                        "SELECT entry_key FROM accounting_template_entry WHERE tenant_id = ? ORDER BY 1",
                        String.class,
                        DEFAULT_TENANT))
                .as("what the applier recorded follows the codes")
                .containsExactly("ACCOUNT:6040", "ACCOUNT:6100", "STATEMENT_LINE:LABOR_OVERHEAD:6100");

        // The template: the new codes only, S15's accounts, the CASH_SHORT mapping on 6040, nine categories.
        Map<String, UUID> template = idsByCode(jdbc, PLATFORM);
        assertThat(template)
                .containsKeys("1080", "3000", "3900", "6040", "6295", "6375", "6380", "6100", "6102", "6105", "4940")
                .doesNotContainKeys("6010", "6015", "6025", "6115", "6900");
        assertThat(jdbc.queryForObject(
                        "SELECT account_subtype FROM gl_account WHERE tenant_id = ? AND account_code = '1080'",
                        String.class,
                        PLATFORM))
                .isEqualTo("CASH_ON_HAND");
        assertThat(platformMappings(jdbc))
                .containsEntry("REGISTER_OVER_SHORT/CASH_SHORT", "6040")
                .containsEntry("REGISTER_CASH_MOVEMENT/PETTY_EXPENSE_SHOP_SUPPLIES", "6340")
                .containsEntry("REGISTER_CASH_MOVEMENT/PETTY_EXPENSE_SMALL_TOOLS", "6430")
                .containsEntry("REGISTER_CASH_MOVEMENT/PETTY_EXPENSE_OFFICE_SUPPLIES", "6370")
                .containsEntry("REGISTER_CASH_MOVEMENT/PETTY_EXPENSE_BUILDING_REPAIRS", "6210")
                .containsEntry("REGISTER_CASH_MOVEMENT/PETTY_EXPENSE_EQUIPMENT_REPAIRS", "6410")
                .containsEntry("REGISTER_CASH_MOVEMENT/PETTY_EXPENSE_POSTAGE_SHIPPING", "6380")
                .containsEntry("REGISTER_CASH_MOVEMENT/PETTY_EXPENSE_CLEANING_JANITORIAL", "6375")
                .containsEntry("REGISTER_CASH_MOVEMENT/PETTY_EXPENSE_STAFF_MEALS", "6295")
                .containsEntry("REGISTER_CASH_MOVEMENT/PETTY_EXPENSE_VEHICLE_FUEL", "6250")
                .containsEntry("REGISTER_CASH_MOVEMENT/CASH_CLEARING", "1095")
                .containsEntry("REGISTER_CASH_MOVEMENT/ACCOUNTS_PAYABLE", "2000")
                .containsEntry("BANK_DEPOSIT/UNDEPOSITED_FUNDS", "1090")
                .containsEntry("BANK_DEPOSIT/CASH_CLEARING", "1095")
                .containsEntry("REGISTER_FLOAT/REGISTER_FLOAT", "1080")
                .containsEntry("REGISTER_FLOAT/OPENING_BALANCE_EQUITY", "3900");
        assertThat(jdbc.queryForList(
                        "SELECT g.account_code || ':' || s.statement_line_code FROM statement_line_mappings s JOIN"
                                + " gl_account g ON g.tenant_id = s.tenant_id AND g.gl_account_id = s.gl_account_id"
                                + " WHERE s.tenant_id = ? AND s.statement_type = 'BALANCE_SHEET' AND g.account_code IN"
                                + " ('1080', '3000', '3900') ORDER BY 1",
                        String.class,
                        PLATFORM))
                .as("1080 is kept in drawers, never in the bank; 3000 and 3900 have lines of their own")
                .containsExactly("1080:BS_KEPT_IN_DRAWERS", "3000:BS_OWNER_EQUITY", "3900:BS_OPENING_BALANCE_EQUITY");
        assertThat(count(jdbc, "petty_expense_category", PLATFORM)).isEqualTo(9);

        // A second seed run changes nothing but its own timestamps.
        List<String> templateBefore = templateRows(jdbc);
        runScript(database, "db/migration/R__seed_reference_accounting.sql");
        assertThat(templateRows(jdbc)).isEqualTo(templateBefore);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM pg_class WHERE relname IN ('gl_account', 'gl_mapping',"
                                + " 'default_gl_mapping', 'statement_line_mappings', 'accounting_template_entry',"
                                + " 'petty_expense_category', 'petty_expense_category_change', 'register_float',"
                                + " 'register_float_change') AND relforcerowsecurity",
                        Long.class))
                .as("FORCE ROW LEVEL SECURITY is on every table V11 touched or created")
                .isEqualTo(9);
    }

    private static Map<String, UUID> idsByCode(JdbcTemplate jdbc, UUID tenant) {
        Map<String, UUID> ids = new HashMap<>();
        jdbc.query(
                "SELECT account_code, gl_account_id FROM gl_account WHERE tenant_id = ?",
                rs -> {
                    ids.put(rs.getString(1), UUID.fromString(rs.getString(2)));
                },
                tenant);
        return ids;
    }

    private static Map<String, String> platformMappings(JdbcTemplate jdbc) {
        Map<String, String> mappings = new HashMap<>();
        jdbc.query(
                "SELECT c.category_name || '/' || k.key_name, g.account_code FROM gl_mapping m"
                        + " JOIN mapping_key k ON k.tenant_id = m.tenant_id AND k.mapping_key_id = m.mapping_key_id"
                        + " JOIN posting_category c ON c.tenant_id = m.tenant_id AND c.posting_category_id ="
                        + " m.posting_category_id JOIN gl_account g ON g.tenant_id = m.tenant_id AND g.gl_account_id ="
                        + " m.gl_account_id WHERE m.tenant_id = ?",
                rs -> {
                    mappings.put(rs.getString(1), rs.getString(2));
                },
                PLATFORM);
        return mappings;
    }

    private static List<String> templateRows(JdbcTemplate jdbc) {
        return jdbc.queryForList(
                ("SELECT 'a ' || account_code || ' ' || account_name || ' ' || account_type || ' '"
                                + " || coalesce(account_subtype, '-') FROM gl_account WHERE tenant_id = ?1"
                                + " UNION ALL SELECT 'm ' || gl_mapping_id || ' ' || gl_account_id FROM gl_mapping WHERE tenant_id = ?1"
                                + " UNION ALL SELECT 'k ' || mapping_key_id || ' ' || key_name FROM mapping_key WHERE tenant_id = ?1"
                                + " UNION ALL SELECT 's ' || mapping_id || ' ' || statement_line_code FROM statement_line_mappings"
                                + " WHERE tenant_id = ?1"
                                + " UNION ALL SELECT 'p ' || code || ' ' || label || ' ' || status FROM petty_expense_category"
                                + " WHERE tenant_id = ?1 ORDER BY 1")
                        .replace("?1", "'" + PLATFORM + "'"),
                String.class);
    }

    private static int count(JdbcTemplate jdbc, String table, UUID tenant) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE tenant_id = ?", Integer.class, tenant);
    }

    private static void runScript(DataSource database, String resource) throws SQLException {
        try (Connection connection = database.getConnection()) {
            connection.setAutoCommit(false);
            ScriptUtils.executeSqlScript(connection, new ClassPathResource(resource));
            connection.commit();
        }
    }
}
