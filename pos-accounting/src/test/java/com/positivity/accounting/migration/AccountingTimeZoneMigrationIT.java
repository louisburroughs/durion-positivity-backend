package com.positivity.accounting.migration;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.accounting.AccountingPostgresContainer;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * V10 (#2558) gives every tenant that already has accounting data the {@code UTC} accounting time zone, keeps a zone a
 * tenant already has, skips the platform tenant, and restores FORCE ROW LEVEL SECURITY on every table it touched.
 */
@DisplayName("V10: the accounting time zone seed (#2558, real Postgres)")
class AccountingTimeZoneMigrationIT {

    private static final UUID PERIOD_TENANT = UUID.fromString("00000000-0000-7000-8000-0000000c2558");
    private static final UUID STATE_TENANT = UUID.fromString("00000000-0000-7000-8000-0000000d2558");
    private static final UUID CHICAGO_TENANT = UUID.fromString("00000000-0000-7000-8000-0000000e2558");
    private static final UUID PLATFORM = UUID.fromString("01900000-0000-7000-8000-000000000000");

    @Test
    @DisplayName("existing tenants get UTC, a chosen zone stays, the platform tenant gets none, FORCE is restored")
    void seedsExistingTenants() {
        DataSource database = AccountingPostgresContainer.ownerDataSource("accounting-time-zone-seed");
        JdbcTemplate jdbc = new JdbcTemplate(database);
        Flyway.configure()
                .dataSource(database)
                .locations("classpath:db/migration")
                .target("9")
                .load()
                .migrate();
        jdbc.update(
                "INSERT INTO accounting_period (tenant_id, period_id, period_code, start_date, end_date, status,"
                        + " created_at, created_by, modified_at, modified_by, version) VALUES (?, ?, '2026-01',"
                        + " DATE '2026-01-01', DATE '2026-01-31', 'OPEN', TIMESTAMPTZ '2026-01-01 00:00:00+00', 't',"
                        + " TIMESTAMPTZ '2026-01-01 00:00:00+00', 't', 0)",
                PERIOD_TENANT,
                UUID.randomUUID());
        jdbc.update(
                "INSERT INTO accounting_template_state (tenant_id, state_id, created_at, modified_at) VALUES (?, ?,"
                        + " TIMESTAMPTZ '2026-01-01 00:00:00+00', TIMESTAMPTZ '2026-01-01 00:00:00+00')",
                STATE_TENANT,
                UUID.randomUUID());
        for (UUID tenant : List.of(CHICAGO_TENANT, PLATFORM)) {
            jdbc.update(
                    "INSERT INTO accounting_configuration (tenant_id, config_id, config_key, config_value, created_at,"
                            + " created_by, modified_at, modified_by) VALUES (?, ?, ?, ?, TIMESTAMPTZ '2026-01-01"
                            + " 00:00:00+00', 't', TIMESTAMPTZ '2026-01-01 00:00:00+00', 't')",
                    tenant,
                    UUID.randomUUID(),
                    tenant.equals(PLATFORM) ? "RETREAD_PLANT_ADD_ON" : "ACCOUNTING_TIME_ZONE",
                    tenant.equals(PLATFORM) ? "false" : "America/Chicago");
        }

        Flyway.configure()
                .dataSource(database)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        Map<UUID, String> zones = new java.util.HashMap<>();
        jdbc.query(
                "SELECT tenant_id, config_value FROM accounting_configuration WHERE config_key = 'ACCOUNTING_TIME_ZONE'",
                rs -> {
                    zones.put(UUID.fromString(rs.getString(1)), rs.getString(2));
                });
        assertThat(zones)
                .containsEntry(PERIOD_TENANT, "UTC")
                .containsEntry(STATE_TENANT, "UTC")
                .containsEntry(CHICAGO_TENANT, "America/Chicago")
                .doesNotContainKey(PLATFORM);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM pg_class WHERE relname IN ('accounting_configuration',"
                                + " 'accounting_template_state', 'accounting_period', 'gl_account', 'journal_entry')"
                                + " AND relforcerowsecurity",
                        Long.class))
                .as("FORCE ROW LEVEL SECURITY is restored on all five tables")
                .isEqualTo(5);
    }
}
