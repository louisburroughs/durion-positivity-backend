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
 * V18 on a database the last release left (CAP:550 S13, #2510; ruling 6): {@code approved_by_kind} is SYSTEM where
 * {@code approved_by = 'SYSTEM'} (the legacy unconditional automatic approval), PERSON where any other approver is
 * recorded, null where none is; the check allows only PERSON and SYSTEM.
 */
@DisplayName("V18: vendor_bill.approved_by_kind and its backfill (#2510, real Postgres)")
class VendorBillApprovedByKindMigrationIT {

    private static final UUID TENANT = UUID.fromString("00000000-0000-7000-8000-0000000c2510");

    @Test
    @DisplayName("Ruling 6: SYSTEM for approved_by SYSTEM, PERSON for a person, null for a bill never approved")
    void backfillsWhoApproved() {
        DataSource database = AccountingPostgresContainer.ownerDataSource("vendor-bill-approved-by-kind");
        JdbcTemplate jdbc = new JdbcTemplate(database);
        Flyway.configure()
                .placeholders(AccountingMigrations.placeholders())
                .dataSource(database)
                .locations(AccountingMigrations.releasedUpTo(17))
                .load()
                .migrate();
        UUID bySystem = bill(jdbc, "INV-S", "APPROVED", "SYSTEM");
        UUID byPerson = bill(jdbc, "INV-P", "APPROVED", "controller.cfo");
        UUID voidedAfterApproval = bill(jdbc, "INV-V", "VOIDED", "gm.gary");
        UUID never = bill(jdbc, "INV-N", "AWAITING_APPROVAL", null);

        Flyway.configure()
                .placeholders(AccountingMigrations.placeholders())
                .dataSource(database)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        assertThat(kind(jdbc, bySystem)).isEqualTo("SYSTEM");
        assertThat(kind(jdbc, byPerson)).isEqualTo("PERSON");
        assertThat(kind(jdbc, voidedAfterApproval)).isEqualTo("PERSON");
        assertThat(kind(jdbc, never)).isNull();
        assertThatThrownBy(() -> jdbc.update(
                        "UPDATE vendor_bill SET approved_by_kind = 'ROBOT' WHERE vendor_bill_id = ?", byPerson))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private static String kind(JdbcTemplate jdbc, UUID billId) {
        return jdbc.queryForObject(
                "SELECT approved_by_kind FROM vendor_bill WHERE vendor_bill_id = ?", String.class, billId);
    }

    private static UUID bill(JdbcTemplate jdbc, String number, String status, String approvedBy) {
        UUID billId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO vendor_bill (tenant_id, vendor_bill_id, vendor_id, bill_number, bill_number_key,"
                        + " bill_date, total_amount, status, approved_by, created_at, modified_at, created_by,"
                        + " modified_by) VALUES (?, ?, ?, ?, ?, TIMESTAMP '2026-09-01 00:00:00', 10.00, ?, ?,"
                        + " TIMESTAMPTZ '2026-09-01 00:00:00+00', TIMESTAMPTZ '2026-09-01 00:00:00+00', 't', 't')",
                TENANT,
                billId,
                UUID.randomUUID(),
                number,
                number.replace("-", ""),
                status,
                approvedBy);
        return billId;
    }
}
