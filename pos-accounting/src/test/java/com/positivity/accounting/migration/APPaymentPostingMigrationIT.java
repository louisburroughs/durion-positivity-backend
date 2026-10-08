package com.positivity.accounting.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.accounting.AccountingMigrations;
import com.positivity.accounting.AccountingPostgresContainer;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * V19 on a database at V18 (CAP:550 S42, #2603; AW40, AW41): {@code payment_date} becomes the payment's business
 * {@code DATE}, {@code net_amount} is dropped, the override columns arrive, {@code bank_account_id} references {@code
 * gl_account}, and every {@code AP_PAYMENT_GL_POSTING} event not already PROCESSED is closed SKIPPED / {@code
 * RETIRED_EVENT_TYPE}.
 */
@DisplayName("V19: AP payment posting schema and the retired AP_PAYMENT_GL_POSTING events (#2603, real Postgres)")
class APPaymentPostingMigrationIT {

    private static final UUID TENANT = UUID.fromString("00000000-0000-7000-8000-0000000c2603");

    @Test
    @DisplayName("V18 → V19: the columns change, the foreign key holds, the retired events close")
    void migratesFromV18() {
        DataSource database = AccountingPostgresContainer.ownerDataSource("ap-payment-posting");
        JdbcTemplate jdbc = new JdbcTemplate(database);
        Flyway.configure()
                .placeholders(AccountingMigrations.placeholders())
                .dataSource(database)
                .locations(AccountingMigrations.releasedUpTo(18))
                .load()
                .migrate();
        UUID legacy = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO ap_payment (tenant_id, payment_id, payment_ref, vendor_id, currency, gross_amount,"
                        + " net_amount, status, payment_date, created_at, created_by) VALUES (?, ?, 'PAY-LEGACY', ?,"
                        + " 'USD', 100.00, 99.00, 'GL_POST_PENDING', TIMESTAMP '2026-09-30 23:30:00',"
                        + " TIMESTAMPTZ '2026-09-30 23:30:00+00', 't')",
                TENANT,
                legacy,
                UUID.randomUUID());
        UUID failed = event(jdbc, "AP_PAYMENT_GL_POSTING", "FAILED");
        UUID suspended = event(jdbc, "AP_PAYMENT_GL_POSTING", "SUSPENDED");
        UUID processed = event(jdbc, "AP_PAYMENT_GL_POSTING", "PROCESSED");
        UUID otherType = event(jdbc, "INVOICE_PAYMENT", "FAILED");

        Flyway.configure()
                .placeholders(AccountingMigrations.placeholders())
                .dataSource(database)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        assertThat(jdbc.queryForObject(
                        "SELECT data_type FROM information_schema.columns WHERE table_name = 'ap_payment'"
                                + " AND column_name = 'payment_date'",
                        String.class))
                .isEqualTo("date");
        assertThat(jdbc.queryForObject(
                        "SELECT payment_date FROM ap_payment WHERE payment_id = ?", LocalDate.class, legacy))
                .isEqualTo(LocalDate.of(2026, 9, 30));
        List<String> columns = jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_name = 'ap_payment'", String.class);
        assertThat(columns)
                .doesNotContain("net_amount")
                .contains("period_override_justification", "period_override_by", "bank_account_id");
        assertThatThrownBy(() -> jdbc.update(
                        "UPDATE ap_payment SET bank_account_id = ? WHERE payment_id = ?", UUID.randomUUID(), legacy))
                .as("bank_account_id references gl_account")
                .isInstanceOf(DataIntegrityViolationException.class);

        for (UUID retired : new UUID[] {failed, suspended}) {
            assertThat(jdbc.queryForMap(
                            "SELECT status, failure_reason_code FROM accounting_event WHERE event_id = ?", retired))
                    .containsEntry("status", "SKIPPED")
                    .containsEntry("failure_reason_code", "RETIRED_EVENT_TYPE");
        }
        assertThat(jdbc.queryForObject(
                        "SELECT status FROM accounting_event WHERE event_id = ?", String.class, processed))
                .isEqualTo("PROCESSED");
        assertThat(jdbc.queryForMap(
                        "SELECT status, failure_reason_code FROM accounting_event WHERE event_id = ?", otherType))
                .containsEntry("status", "FAILED")
                .containsEntry("failure_reason_code", null);
    }

    private static UUID event(JdbcTemplate jdbc, String type, String status) {
        UUID eventId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO accounting_event (tenant_id, event_id, event_type, status, source_system, received_at,"
                        + " transaction_date, version, payload) VALUES (?, ?, ?, ?, 'pos-accounting',"
                        + " TIMESTAMPTZ '2026-09-01 00:00:00+00', TIMESTAMP '2026-09-01 00:00:00', 0, '{}'::jsonb)",
                TENANT,
                eventId,
                type,
                status);
        return eventId;
    }
}
