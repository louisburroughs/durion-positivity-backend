package com.positivity.accounting.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.accounting.AccountingMigrations;
import com.positivity.accounting.AccountingPostgresContainer;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * V15 (#2577 AC3; ADR-0067 R-1, R-2, R-4): register_float and register_float_change gain {@code currency_code NOT
 * NULL}. Rows written before it take the ledger currency from the {@code ledger_currency} placeholder, never a
 * literal: the upgrade here runs with CAD, so a backfilled USD would mean a hard-coded default. Afterwards the columns
 * carry no default, so a row that does not state its currency is refused.
 */
@DisplayName("V15: register float currency_code, backfilled with the ledger currency (#2577, real Postgres)")
class RegisterFloatCurrencyMigrationIT {

    private static final UUID TENANT = UUID.fromString("00000000-0000-7000-8000-0000000a2577");
    private static final UUID LOCATION = UUID.fromString("019a0000-0000-7000-8000-00000000a001");

    @Test
    @DisplayName("AC3: existing floats and history rows are backfilled from the placeholder; the columns are NOT NULL"
            + " with no default")
    void backfillsExistingRowsWithTheLedgerCurrency() {
        DataSource database = AccountingPostgresContainer.ownerDataSource("register-float-currency");
        JdbcTemplate jdbc = new JdbcTemplate(database);
        // The database as #2571 left it: V1-V13.
        Flyway.configure()
                .placeholders(AccountingMigrations.placeholders())
                .dataSource(database)
                .locations(AccountingMigrations.releasedUpTo(13))
                .target("13")
                .load()
                .migrate();
        UUID registerFloat = UUID.fromString("019a0000-0000-7000-8000-0000000f2577");
        jdbc.update(
                "INSERT INTO register_float (tenant_id, register_float_id, register_id, location_id, amount,"
                        + " go_live_journal_entry_id, version, created_at, modified_at) VALUES (?, ?, 'T-1', ?, 200.0000,"
                        + " ?, 1, TIMESTAMPTZ '2026-10-01 09:00:00+00', TIMESTAMPTZ '2026-10-01 09:00:00+00')",
                TENANT,
                registerFloat,
                LOCATION,
                UUID.randomUUID());
        for (String kind : new String[] {"GO_LIVE", "CHANGE"}) {
            jdbc.update(
                    "INSERT INTO register_float_change (tenant_id, change_id, register_float_id, register_id,"
                            + " location_id, kind, previous_amount, new_amount, journal_entry_id, effective_date,"
                            + " justification, actor, request_id, created_at, modified_at) VALUES (?, ?, ?, 'T-1', ?, ?,"
                            + " 0, 200.0000, ?, DATE '2026-10-01', 'Counted float in drawer 1 at go-live', 'cfo', ?,"
                            + " TIMESTAMPTZ '2026-10-01 09:00:00+00', TIMESTAMPTZ '2026-10-01 09:00:00+00')",
                    TENANT,
                    UUID.randomUUID(),
                    registerFloat,
                    LOCATION,
                    kind,
                    UUID.randomUUID(),
                    UUID.randomUUID());
        }

        Flyway.configure()
                .placeholders(Map.of("ledger_currency", "CAD"))
                .dataSource(database)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        assertThat(jdbc.queryForList(
                        "SELECT currency_code FROM register_float WHERE tenant_id = ?", String.class, TENANT))
                .containsExactly("CAD");
        assertThat(jdbc.queryForList(
                        "SELECT currency_code FROM register_float_change WHERE tenant_id = ?", String.class, TENANT))
                .containsExactly("CAD", "CAD");
        for (String table : new String[] {"register_float", "register_float_change"}) {
            Map<String, Object> column = jdbc.queryForMap(
                    "SELECT is_nullable, column_default, character_maximum_length FROM information_schema.columns"
                            + " WHERE table_schema = 'public' AND table_name = ? AND column_name = 'currency_code'",
                    table);
            assertThat(column.get("is_nullable"))
                    .as("%s.currency_code is NOT NULL", table)
                    .isEqualTo("NO");
            assertThat(column.get("column_default"))
                    .as("%s.currency_code has no default", table)
                    .isNull();
            assertThat(column.get("character_maximum_length")).isEqualTo(3);
        }
        assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO register_float (tenant_id, register_float_id, register_id, location_id, amount,"
                                + " version, created_at, modified_at) VALUES (?, ?, 'T-2', ?, 0, 0,"
                                + " TIMESTAMPTZ '2026-10-02 09:00:00+00', TIMESTAMPTZ '2026-10-02 09:00:00+00')",
                        TENANT,
                        UUID.randomUUID(),
                        LOCATION))
                .as("a float that does not state its currency is refused")
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
