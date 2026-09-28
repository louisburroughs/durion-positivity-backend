package com.positivity.accounting.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.accounting.AccountingPostgresContainer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The bank reconciliation schema of story S1 (#2300; durion SPEC-manual-bank-reconciliation §6.4) as Flyway
 * builds it from the flattened baseline on an empty Postgres: every table and column §6.4 assigns to S1, the
 * database-held invariants U1/U2/U4/O1, the corrected {@code 1000 Cash} seed — and the constraints §6.4 assigns
 * to story S4 (the adjustment link CHECKs, the {@code TRANSFER} value, the bridge unique) still absent.
 *
 * <p>H2 enforces none of the partial uniques or the exclusion constraint, so these run on Postgres only. Each
 * test works in its own transaction and rolls back. Requires Docker.
 */
@DisplayName("Bank reconciliation schema (story S1, #2300) on Postgres")
class BankReconciliationSchemaIT {

    private static final DataSource DATABASE =
            AccountingPostgresContainer.ownerDataSource("bank-reconciliation-schema");

    /** The alpha default tenant, which the seed binds (ADR-0062). */
    private static final String TENANT_ID = "01900000-0000-7000-8000-000000000001";

    /** Seeded {@code 1000 Cash} (R__seed_reference_accounting.sql). */
    private static final UUID CASH_ACCOUNT_ID = UUID.fromString("5eed0acc-0000-4000-8000-000000001000");

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(DATABASE)
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @Nested
    @DisplayName("tables and columns (criterion 3)")
    class TablesAndColumns {

        @Test
        @DisplayName("every S1 table exists and bank_reconciliation_line does not")
        void tablesExist() throws SQLException {
            try (Connection c = open()) {
                for (String table : List.of(
                        "bank_account_profile",
                        "bank_statement",
                        "bank_transaction",
                        "bank_import",
                        "bank_import_file",
                        "bank_import_row",
                        "bank_reconciliation_match",
                        "bank_reconciliation_bank_match",
                        "bank_reconciliation_outstanding_item")) {
                    assertThat(tableExists(c, table)).as(table).isTrue();
                }
                assertThat(tableExists(c, "bank_reconciliation_line")).isFalse();
                c.rollback();
            }
        }

        @Test
        @DisplayName("the D2 columns are in place and nullable")
        void d2ColumnsAreNullable() throws SQLException {
            try (Connection c = open()) {
                assertNullable(c, "bank_account_profile", "reconciliation_baseline_date");
                assertNullable(c, "bank_statement", "gap_acknowledgement");
                assertNullable(c, "bank_statement", "gap_acknowledged_by");
                assertNullable(c, "bank_statement", "gap_acknowledged_at");
                assertNullable(c, "bank_reconciliation", "baseline_date");
                assertNullable(c, "bank_reconciliation", "sum_opening_adjustments");
                assertNullable(c, "bank_reconciliation", "statement_id");
                assertNullable(c, "bank_reconciliation_match", "replaces_match_id");
                for (String column : List.of(
                        "request_id",
                        "transaction_date",
                        "posted_period_code",
                        "bank_transaction_id",
                        "override_justification",
                        "status",
                        "reversal_journal_entry_id",
                        "counter_gl_account_id",
                        "justification",
                        "settles_match_id",
                        "bridges_statement_id")) {
                    assertNullable(c, "bank_reconciliation_adjustment", column);
                }
                assertNullable(c, "bank_reconciliation_outstanding_item", "closed_on");
                assertNullable(c, "bank_reconciliation_outstanding_item", "reaffirm_justification");
                assertThat(columnExists(c, "bank_reconciliation", "statement_date"))
                        .isFalse();
                assertThat(columnExists(c, "bank_reconciliation", "period_start_date"))
                        .isFalse();
                assertThat(columnExists(c, "bank_reconciliation", "statement_closing_balance"))
                        .isTrue();
                assertThat(columnExists(c, "bank_reconciliation", "version")).isTrue();
                assertThat(columnExists(c, "bank_reconciliation_gl_match", "active"))
                        .isTrue();
                c.rollback();
            }
        }

        @Test
        @DisplayName("the S1 constraints and indexes exist; S4's adjustment constraints do not")
        void constraintsBelongToTheirStory() throws SQLException {
            try (Connection c = open()) {
                assertThat(constraintType(c, "bank_statement_no_overlap_ex")).isEqualTo("x");
                assertThat(indexes(c))
                        .contains(
                                "bank_statement_committed_window_uk",
                                "bank_statement_baseline_idx",
                                "bank_transaction_source_id_uk",
                                "bank_import_committed_file_uk",
                                "bank_reconciliation_active_statement_uk",
                                "bank_reconciliation_gl_match_active_gl_line_uk",
                                "bank_reconciliation_bank_match_active_txn_uk",
                                "bank_reconciliation_outstanding_item_open_gl_line_uk",
                                "bank_reconciliation_outstanding_item_open_txn_uk",
                                "idx_audit_log_tenant_entity_time");
                assertThat(constraintType(c, "bank_reconciliation_gl_match_gl_line_uk"))
                        .as("the F2 full unique on gl_line_id is replaced by the partial one")
                        .isNull();
                assertThat(checkConstraints(c, "bank_reconciliation_adjustment"))
                        .as("only the unchanged type CHECK and the status CHECK — the link, counter and TRANSFER"
                                + " rules are story S4's")
                        .containsExactlyInAnyOrder(
                                "bank_reconciliation_adjustment_type_ck", "bank_reconciliation_adjustment_status_ck");
                assertThat(indexes(c)).noneMatch(name -> name.contains("bridge"));
                c.rollback();
            }
        }
    }

    @Nested
    @DisplayName("bank statements (U1, U2 — criterion 5)")
    class Statements {

        @Test
        @DisplayName("an overlapping COMMITTED window on the same account is refused by the exclusion constraint")
        void overlappingCommittedWindowRefused() throws SQLException {
            try (Connection c = open()) {
                insertStatement(c, LocalDate.of(2031, 9, 1), LocalDate.of(2031, 9, 30), "COMMITTED");

                assertThatThrownBy(() ->
                                insertStatement(c, LocalDate.of(2031, 9, 15), LocalDate.of(2031, 10, 14), "COMMITTED"))
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("bank_statement_no_overlap_ex");
                c.rollback();
            }
        }

        @Test
        @DisplayName("a SUPERSEDED statement may overlap a COMMITTED one (the constraint is partial)")
        void supersededOverlapAccepted() throws SQLException {
            try (Connection c = open()) {
                insertStatement(c, LocalDate.of(2031, 9, 1), LocalDate.of(2031, 9, 30), "SUPERSEDED");
                insertStatement(c, LocalDate.of(2031, 9, 15), LocalDate.of(2031, 10, 14), "COMMITTED");
                insertStatement(c, LocalDate.of(2031, 9, 1), LocalDate.of(2031, 9, 30), "SUPERSEDED");
                c.rollback();
            }
        }

        @Test
        @DisplayName("adjacent COMMITTED windows are accepted")
        void adjacentWindowsAccepted() throws SQLException {
            try (Connection c = open()) {
                insertStatement(c, LocalDate.of(2031, 9, 1), LocalDate.of(2031, 9, 30), "COMMITTED");
                insertStatement(c, LocalDate.of(2031, 10, 1), LocalDate.of(2031, 10, 31), "COMMITTED");
                c.rollback();
            }
        }

        @Test
        @DisplayName("a window ending before it starts is refused")
        void invertedWindowRefused() throws SQLException {
            try (Connection c = open()) {
                assertThatThrownBy(() ->
                                insertStatement(c, LocalDate.of(2031, 9, 30), LocalDate.of(2031, 9, 1), "COMMITTED"))
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("bank_statement_dates_ck");
                c.rollback();
            }
        }
    }

    @Nested
    @DisplayName("matches (U4 — criterion 6)")
    class Matches {

        @Test
        @DisplayName("two ACTIVE gl_match rows on one GL line are refused; an inactive one leaves room for the next")
        void glLineInAtMostOneActiveMatch() throws SQLException {
            try (Connection c = open()) {
                UUID reconciliationId = insertReconciliation(c);
                UUID glLineId = UUID.randomUUID();
                UUID first = insertMatchHeader(c, reconciliationId);
                UUID second = insertMatchHeader(c, reconciliationId);
                UUID firstMember = insertGlMatch(c, reconciliationId, first, glLineId, true);

                Savepoint beforeDuplicate = c.setSavepoint();
                assertThatThrownBy(() -> insertGlMatch(c, reconciliationId, second, glLineId, true))
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("bank_reconciliation_gl_match_active_gl_line_uk");
                c.rollback(beforeDuplicate);

                update(c, "UPDATE bank_reconciliation_gl_match SET active = false WHERE id = ?", firstMember);
                insertGlMatch(c, reconciliationId, second, glLineId, true);
                c.rollback();
            }
        }

        @Test
        @DisplayName("a bank transaction is in at most one ACTIVE bank_match")
        void bankTransactionInAtMostOneActiveMatch() throws SQLException {
            try (Connection c = open()) {
                UUID reconciliationId = insertReconciliation(c);
                UUID statementId = insertStatement(c, LocalDate.of(2032, 1, 1), LocalDate.of(2032, 1, 31), "COMMITTED");
                UUID transactionId = insertTransaction(c, statementId);
                UUID first = insertMatchHeader(c, reconciliationId);
                UUID second = insertMatchHeader(c, reconciliationId);
                insertBankMatch(c, first, transactionId, true);

                Savepoint beforeDuplicate = c.setSavepoint();
                assertThatThrownBy(() -> insertBankMatch(c, second, transactionId, true))
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("bank_reconciliation_bank_match_active_txn_uk");
                c.rollback(beforeDuplicate);

                update(c, "UPDATE bank_reconciliation_bank_match SET active = false WHERE match_id = ?", first);
                insertBankMatch(c, second, transactionId, true);
                c.rollback();
            }
        }

        @Test
        @DisplayName("a zero-amount bank transaction is refused")
        void zeroAmountTransactionRefused() throws SQLException {
            try (Connection c = open()) {
                UUID statementId = insertStatement(c, LocalDate.of(2032, 2, 1), LocalDate.of(2032, 2, 28), "COMMITTED");
                assertThatThrownBy(() -> execute(
                                c,
                                "INSERT INTO bank_transaction (bank_transaction_id, gl_account_id, statement_id,"
                                        + " source_kind, settlement_state, transaction_date, signed_amount, currency,"
                                        + " status, created_at, created_by, updated_at)"
                                        + " VALUES (?, ?, ?, 'FILE_IMPORT', 'POSTED', DATE '2032-02-10', 0, 'USD',"
                                        + " 'UNMATCHED', now(), 'it', now())",
                                UUID.randomUUID(),
                                CASH_ACCOUNT_ID,
                                statementId))
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("bank_transaction_signed_amount_ck");
                c.rollback();
            }
        }
    }

    @Nested
    @DisplayName("adjustments (criterion 10) and outstanding items (criterion 11)")
    class AdjustmentsAndOutstandingItems {

        @Test
        @DisplayName("an OTHER adjustment with every new column null (the F2 shape) is accepted")
        void f2OtherAdjustmentShapeAccepted() throws SQLException {
            try (Connection c = open()) {
                UUID reconciliationId = insertReconciliation(c);
                insertAdjustment(c, reconciliationId, "OTHER");
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT status FROM bank_reconciliation_adjustment WHERE reconciliation_id = ?"); ) {
                    ps.setObject(1, reconciliationId);
                    try (ResultSet rs = ps.executeQuery()) {
                        assertThat(rs.next()).isTrue();
                        assertThat(rs.getString(1))
                                .as("status defaults to POSTED")
                                .isEqualTo("POSTED");
                    }
                }
                c.rollback();
            }
        }

        @Test
        @DisplayName("a TRANSFER adjustment is refused by the unchanged type CHECK (TRANSFER ships in S4)")
        void transferRefused() throws SQLException {
            try (Connection c = open()) {
                UUID reconciliationId = insertReconciliation(c);
                assertThatThrownBy(() -> insertAdjustment(c, reconciliationId, "TRANSFER"))
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("bank_reconciliation_adjustment_type_ck");
                c.rollback();
            }
        }

        @Test
        @DisplayName("an outstanding item may be CLEARED_IN_GAP; a ledger line is in at most one OPEN item (O1)")
        void outstandingItemStatuses() throws SQLException {
            try (Connection c = open()) {
                UUID reconciliationId = insertReconciliation(c);
                UUID glLineId = UUID.randomUUID();
                insertOutstandingItem(c, reconciliationId, glLineId, "CLEARED_IN_GAP");
                insertOutstandingItem(c, reconciliationId, glLineId, "OPEN");

                Savepoint beforeDuplicate = c.setSavepoint();
                assertThatThrownBy(() -> insertOutstandingItem(c, reconciliationId, glLineId, "OPEN"))
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("bank_reconciliation_outstanding_item_open_gl_line_uk");
                c.rollback(beforeDuplicate);

                assertThatThrownBy(() -> insertOutstandingItem(c, reconciliationId, UUID.randomUUID(), "PENDING"))
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("bank_reconciliation_outstanding_item_status_ck");
                c.rollback();
            }
        }
    }

    @Test
    @DisplayName("the seed makes 1000 Cash reconcilable BANK_CASH and leaves 1090 as it was (G7, criterion 7)")
    void seedFixesCash() throws SQLException {
        try (Connection c = open();
                PreparedStatement ps =
                        c.prepareStatement("SELECT account_code, account_subtype, reconcilable FROM gl_account"
                                + " WHERE account_code IN ('1000', '1090') ORDER BY account_code");
                ResultSet rs = ps.executeQuery()) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualTo("1000");
            assertThat(rs.getString(2)).isEqualTo("BANK_CASH");
            assertThat(rs.getBoolean(3)).isTrue();
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualTo("1090");
            assertThat(rs.getString(2)).isEqualTo("UNDEPOSITED_FUNDS");
            assertThat(rs.getBoolean(3)).isTrue();
            c.rollback();
        }
    }

    // ---------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------

    private static Connection open() throws SQLException {
        Connection c = DATABASE.getConnection();
        try (PreparedStatement ps = c.prepareStatement("SELECT set_config('app.current_tenant', ?, false)")) {
            ps.setString(1, TENANT_ID);
            ps.execute();
        }
        c.setAutoCommit(false);
        return c;
    }

    private static UUID insertStatement(Connection c, LocalDate start, LocalDate end, String status)
            throws SQLException {
        UUID id = UUID.randomUUID();
        execute(
                c,
                "INSERT INTO bank_statement (statement_id, gl_account_id, source_kind, start_date, end_date,"
                        + " opening_balance, closing_balance, activity_total, currency, status, created_at,"
                        + " created_by, updated_at)"
                        + " VALUES (?, ?, 'FILE_IMPORT', ?, ?, 0, 0, 0, 'USD', ?, now(), 'it', now())",
                id,
                CASH_ACCOUNT_ID,
                start,
                end,
                status);
        return id;
    }

    private static UUID insertTransaction(Connection c, UUID statementId) throws SQLException {
        UUID id = UUID.randomUUID();
        execute(
                c,
                "INSERT INTO bank_transaction (bank_transaction_id, gl_account_id, statement_id, source_kind,"
                        + " settlement_state, transaction_date, signed_amount, currency, status, created_at,"
                        + " created_by, updated_at)"
                        + " VALUES (?, ?, ?, 'FILE_IMPORT', 'POSTED', DATE '2032-01-10', 25.00, 'USD', 'UNMATCHED',"
                        + " now(), 'it', now())",
                id,
                CASH_ACCOUNT_ID,
                statementId);
        return id;
    }

    private static UUID insertReconciliation(Connection c) throws SQLException {
        UUID id = UUID.randomUUID();
        execute(
                c,
                "INSERT INTO bank_reconciliation (reconciliation_id, gl_account_id, statement_start_date,"
                        + " statement_end_date, currency, statement_closing_balance, gl_ending_balance, difference,"
                        + " status, created_at, created_by, updated_at)"
                        + " VALUES (?, ?, DATE '2031-06-01', DATE '2031-06-30', 'USD', 0, 0, 0, 'IN_PROGRESS',"
                        + " now(), 'it', now())",
                id,
                CASH_ACCOUNT_ID);
        return id;
    }

    private static UUID insertMatchHeader(Connection c, UUID reconciliationId) throws SQLException {
        UUID id = UUID.randomUUID();
        execute(
                c,
                "INSERT INTO bank_reconciliation_match (match_id, reconciliation_id, match_kind, state, origin,"
                        + " created_at, updated_at)"
                        + " VALUES (?, ?, 'ONE_TO_ONE', 'ACCEPTED', 'USER', now(), now())",
                id,
                reconciliationId);
        return id;
    }

    private static UUID insertGlMatch(Connection c, UUID reconciliationId, UUID matchId, UUID glLineId, boolean active)
            throws SQLException {
        UUID id = UUID.randomUUID();
        execute(
                c,
                "INSERT INTO bank_reconciliation_gl_match (id, reconciliation_id, match_id, gl_line_id,"
                        + " signed_amount, created_at, active) VALUES (?, ?, ?, ?, 25.00, now(), ?)",
                id,
                reconciliationId,
                matchId,
                glLineId,
                active);
        return id;
    }

    private static void insertBankMatch(Connection c, UUID matchId, UUID transactionId, boolean active)
            throws SQLException {
        execute(
                c,
                "INSERT INTO bank_reconciliation_bank_match (match_id, bank_transaction_id, active, created_at)"
                        + " VALUES (?, ?, ?, now())",
                matchId,
                transactionId,
                active);
    }

    private static void insertAdjustment(Connection c, UUID reconciliationId, String type) throws SQLException {
        execute(
                c,
                "INSERT INTO bank_reconciliation_adjustment (adjustment_id, reconciliation_id, adjustment_type,"
                        + " amount, journal_entry_id, created_at, created_by)"
                        + " VALUES (?, ?, ?, -5.00, ?, now(), 'it')",
                UUID.randomUUID(),
                reconciliationId,
                type,
                UUID.randomUUID());
    }

    private static void insertOutstandingItem(Connection c, UUID reconciliationId, UUID glLineId, String status)
            throws SQLException {
        execute(
                c,
                "INSERT INTO bank_reconciliation_outstanding_item (outstanding_item_id, gl_account_id, side,"
                        + " gl_line_id, item_kind, signed_amount, item_date, registered_in_reconciliation_id,"
                        + " registered_by, registered_at, status, created_at, updated_at)"
                        + " VALUES (?, ?, 'LEDGER', ?, 'DEPOSIT_IN_TRANSIT', 40.00, DATE '2031-06-28', ?, 'it',"
                        + " now(), ?, now(), now())",
                UUID.randomUUID(),
                CASH_ACCOUNT_ID,
                glLineId,
                reconciliationId,
                status);
    }

    private static void execute(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            ps.executeUpdate();
        }
    }

    private static void update(Connection c, String sql, UUID id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, id);
            assertThat(ps.executeUpdate()).isEqualTo(1);
        }
    }

    private static boolean tableExists(Connection c, String table) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT 1 FROM information_schema.tables WHERE table_schema = 'public' AND table_name = ?")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static boolean columnExists(Connection c, String table, String column) throws SQLException {
        return isNullable(c, table, column) != null;
    }

    private static void assertNullable(Connection c, String table, String column) throws SQLException {
        assertThat(isNullable(c, table, column)).as(table + "." + column).isEqualTo("YES");
    }

    private static String isNullable(Connection c, String table, String column) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT is_nullable FROM information_schema.columns"
                + " WHERE table_schema = 'public' AND table_name = ? AND column_name = ?")) {
            ps.setString(1, table);
            ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private static String constraintType(Connection c, String name) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT contype::text FROM pg_constraint WHERE conname = ?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private static List<String> checkConstraints(Connection c, String table) throws SQLException {
        List<String> names = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT conname FROM pg_constraint"
                + " WHERE conrelid = ('public.' || ?)::regclass AND contype = 'c'")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    names.add(rs.getString(1));
                }
            }
        }
        return names;
    }

    private static List<String> indexes(Connection c) throws SQLException {
        List<String> names = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT indexname FROM pg_indexes WHERE schemaname = 'public'");
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                names.add(rs.getString(1));
            }
        }
        return names;
    }
}
