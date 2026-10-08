package com.positivity.accounting.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.accounting.AccountingMigrations;
import com.positivity.accounting.AccountingPostgresContainer;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * V17 on a database the last release left (CAP:550 S12, #2509; AW40): every VENDOR_BILL_GL_POSTING event not already
 * PROCESSED is closed SKIPPED with failureReasonCode RETIRED_EVENT_TYPE, so neither the retry job nor the retry
 * endpoint can pick one up; other events are untouched; and the status check takes AWAITING_APPROVAL.
 */
@DisplayName("V17: vendor-bill approval and the retired VENDOR_BILL_GL_POSTING events (#2509, real Postgres)")
class VendorBillApprovalMigrationIT {

    private static final UUID TENANT = UUID.fromString("00000000-0000-7000-8000-0000000c2509");

    @Test
    @DisplayName("AW40: retired events close SKIPPED / RETIRED_EVENT_TYPE; PROCESSED and other types stay as they were")
    void closesTheRetiredEvents() {
        DataSource database = AccountingPostgresContainer.ownerDataSource("vendor-bill-approval");
        JdbcTemplate jdbc = new JdbcTemplate(database);
        Flyway.configure()
                .placeholders(AccountingMigrations.placeholders())
                .dataSource(database)
                .locations(AccountingMigrations.releasedUpTo(16))
                .load()
                .migrate();
        UUID failed = event(jdbc, "VENDOR_BILL_GL_POSTING", "FAILED");
        UUID received = event(jdbc, "VENDOR_BILL_GL_POSTING", "RECEIVED");
        UUID suspended = event(jdbc, "VENDOR_BILL_GL_POSTING", "SUSPENDED");
        UUID processed = event(jdbc, "VENDOR_BILL_GL_POSTING", "PROCESSED");
        // Not AP_PAYMENT_GL_POSTING: V19 retires that one too (CAP:550 S42, #2603).
        UUID otherType = event(jdbc, "INVOICE_PAYMENT", "FAILED");
        assertThatThrownBy(() -> bill(jdbc, "AWAITING_APPROVAL"))
                .as("before V17 the status check does not know AWAITING_APPROVAL")
                .isInstanceOf(DataIntegrityViolationException.class);

        Flyway.configure()
                .placeholders(AccountingMigrations.placeholders())
                .dataSource(database)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        for (UUID retired : new UUID[] {failed, received, suspended}) {
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
        bill(jdbc, "AWAITING_APPROVAL");
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM vendor_bill WHERE tenant_id = ? AND status = 'AWAITING_APPROVAL'",
                        Integer.class,
                        TENANT))
                .isEqualTo(1);
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

    private static void bill(JdbcTemplate jdbc, String status) {
        jdbc.update(
                "INSERT INTO vendor_bill (tenant_id, vendor_bill_id, vendor_id, bill_number, bill_number_key,"
                        + " bill_date, total_amount, status, created_at, modified_at, created_by, modified_by) VALUES (?,"
                        + " ?, ?, 'INV-1', 'INV1', TIMESTAMP '2026-09-01 00:00:00', 10.00, ?, TIMESTAMPTZ '2026-09-01"
                        + " 00:00:00+00', TIMESTAMPTZ '2026-09-01 00:00:00+00', 't', 't')",
                TENANT,
                UUID.randomUUID(),
                UUID.randomUUID(),
                status);
    }
}
