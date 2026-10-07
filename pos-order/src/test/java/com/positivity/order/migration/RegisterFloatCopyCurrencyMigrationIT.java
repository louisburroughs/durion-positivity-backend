package com.positivity.order.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.order.OrderPostgresContainer;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * V5 (#2577; ADR-0067 R-1, R-2, R-4, PC-8): pos-order's register float copy states its currency. Copies written from
 * facts without one take the functional currency from the {@code functional_currency} placeholder, never a literal:
 * the upgrade runs with CAD, so a backfilled USD would mean a hard-coded default. Afterwards the column has no default.
 */
@DisplayName("V5: ext_accounting_register_float.currency_code, backfilled with the functional currency (#2577)")
class RegisterFloatCopyCurrencyMigrationIT {

    private static final UUID TENANT = UUID.fromString("00000000-0000-7000-8000-0000000b2577");

    @Test
    @DisplayName("existing copies take the placeholder's currency; the column is NOT NULL with no default")
    void backfillsExistingCopies() {
        DataSource database = OrderPostgresContainer.ownerDataSource("register-float-copy-currency");
        JdbcTemplate jdbc = new JdbcTemplate(database);
        // The database as S16 (#2512) left it: V1-V4.
        Flyway.configure()
                .placeholders(Map.of("functional_currency", "USD"))
                .dataSource(database)
                .locations("classpath:db/migration")
                .target("4")
                .load()
                .migrate();
        jdbc.update(
                "INSERT INTO ext_accounting_register_float (tenant_id, register_float_id, register_id, location_id,"
                        + " amount, effective_date, aggregate_version, synced_at) VALUES (?, ?, 'T-1', ?, 200.0000,"
                        + " DATE '2026-10-01', 3, TIMESTAMPTZ '2026-10-01 09:00:00+00')",
                TENANT,
                UUID.randomUUID(),
                UUID.randomUUID());

        Flyway.configure()
                .placeholders(Map.of("functional_currency", "CAD"))
                .dataSource(database)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        assertThat(jdbc.queryForList(
                        "SELECT currency_code FROM ext_accounting_register_float WHERE tenant_id = ?",
                        String.class,
                        TENANT))
                .containsExactly("CAD");
        Map<String, Object> column = jdbc.queryForMap(
                "SELECT is_nullable, column_default, character_maximum_length FROM information_schema.columns"
                        + " WHERE table_schema = 'public' AND table_name = 'ext_accounting_register_float'"
                        + " AND column_name = 'currency_code'");
        assertThat(column.get("is_nullable")).isEqualTo("NO");
        assertThat(column.get("column_default")).isNull();
        assertThat(column.get("character_maximum_length")).isEqualTo(3);
        assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO ext_accounting_register_float (tenant_id, register_float_id, register_id,"
                                + " location_id, amount, effective_date, aggregate_version, synced_at) VALUES (?, ?,"
                                + " 'T-2', ?, 0, DATE '2026-10-02', 1, TIMESTAMPTZ '2026-10-02 09:00:00+00')",
                        TENANT,
                        UUID.randomUUID(),
                        UUID.randomUUID()))
                .as("a copy that does not state its currency is refused")
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
