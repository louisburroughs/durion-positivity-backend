package com.positivity.accounting.migration;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.accounting.AccountingPostgresContainer;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * V12 (#2554 follow-up) drops the orphan {@code reconciliation_records} table that the V1 baseline created: present
 * after V11, gone (with its tenant_isolation policy) after V12.
 */
@DisplayName("V12: the orphan reconciliation_records table is dropped (#2554, real Postgres)")
class ReconciliationRecordsDropMigrationIT {

    @Test
    @DisplayName("the table and its policy exist after V11 and are gone after V12")
    void dropsTheOrphanTable() {
        DataSource database = AccountingPostgresContainer.ownerDataSource("reconciliation-records-drop");
        JdbcTemplate jdbc = new JdbcTemplate(database);
        Flyway.configure()
                .placeholders(com.positivity.accounting.AccountingMigrations.placeholders())
                .dataSource(database)
                .locations(com.positivity.accounting.AccountingMigrations.releasedUpTo(11))
                .target("11")
                .load()
                .migrate();
        assertThat(tables(jdbc)).as("V1 created it").isEqualTo(1);
        assertThat(policies(jdbc)).as("with its tenant_isolation policy").isEqualTo(1);

        Flyway.configure()
                .placeholders(com.positivity.accounting.AccountingMigrations.placeholders())
                .dataSource(database)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        assertThat(tables(jdbc)).as("V12 dropped it").isZero();
        assertThat(policies(jdbc)).as("and its policy").isZero();
    }

    private static Integer tables(JdbcTemplate jdbc) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace"
                        + " WHERE n.nspname = 'public' AND c.relname = 'reconciliation_records'",
                Integer.class);
    }

    private static Integer policies(JdbcTemplate jdbc) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM pg_policies WHERE schemaname = 'public' AND tablename = 'reconciliation_records'",
                Integer.class);
    }
}
