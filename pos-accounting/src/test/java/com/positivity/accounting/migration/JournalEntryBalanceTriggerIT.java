package com.positivity.accounting.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Proves the Story A1 (issue #935) DB-level balance backstop added in
 * {@code V8__je_balance_constraint.sql} against a real Postgres.
 *
 * <p>The default test suite runs on H2 with Flyway disabled, so the PL/pgSQL
 * constraint triggers are never exercised there. This IT runs the full Flyway
 * chain (V1..Vn + repeatable seed) on a Testcontainers Postgres and drives the
 * triggers with direct SQL — the exact bypass path the storage backstop exists
 * to close. The service-layer check ({@code JournalEntryServiceImpl.validateBalance})
 * remains the friendly API-level validation and is untouched by this story.
 *
 * <p>Skipped cleanly when Docker is unavailable ({@code disabledWithoutDocker}).
 */
@Testcontainers(disabledWithoutDocker = true)
class JournalEntryBalanceTriggerIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    /** (i) Direct SQL creating an unbalanced POSTED entry fails at commit (deferred trigger). */
    @Test
    void unbalancedPostedEntry_rejectedAtCommit() throws SQLException {
        try (Connection c = open()) {
            UUID glAccountId = anyGlAccountId(c);
            UUID entryId = insertEntry(c, "POSTED");
            insertLine(c, entryId, glAccountId, 1, new BigDecimal("100.0000"), BigDecimal.ZERO);
            insertLine(c, entryId, glAccountId, 2, BigDecimal.ZERO, new BigDecimal("99.0000"));

            assertThatThrownBy(c::commit)
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("is POSTED but unbalanced")
                    .hasMessageContaining(entryId.toString());
        }
        assertThat(unbalancedPostedEntryCount()).isZero(); // failed commit persisted nothing
    }

    /** (ii) A DRAFT entry may hold unbalanced lines (transiently unbalanced while edited). */
    @Test
    void draftEntry_mayHoldUnbalancedLines() throws SQLException {
        UUID entryId;
        try (Connection c = open()) {
            UUID glAccountId = anyGlAccountId(c);
            entryId = insertEntry(c, "DRAFT");
            insertLine(c, entryId, glAccountId, 1, new BigDecimal("100.0000"), BigDecimal.ZERO);
            insertLine(c, entryId, glAccountId, 2, BigDecimal.ZERO, new BigDecimal("25.0000"));
            c.commit();
        }
        assertThat(entryStatus(entryId)).isEqualTo("DRAFT");
    }

    /** (iii) A balanced POSTED entry commits. */
    @Test
    void balancedPostedEntry_commits() throws SQLException {
        UUID entryId;
        try (Connection c = open()) {
            UUID glAccountId = anyGlAccountId(c);
            entryId = insertEntry(c, "POSTED");
            insertLine(c, entryId, glAccountId, 1, new BigDecimal("250.5000"), BigDecimal.ZERO);
            insertLine(c, entryId, glAccountId, 2, BigDecimal.ZERO, new BigDecimal("250.5000"));
            c.commit();
        }
        assertThat(entryStatus(entryId)).isEqualTo("POSTED");
    }

    /**
     * (iv) Flipping DRAFT to POSTED with unbalanced lines fails at commit, even though no
     * journal_entry_line row changes in the posting transaction (journal_entry status trigger).
     */
    @Test
    void draftFlippedToPosted_withUnbalancedLines_rejectedAtCommit() throws SQLException {
        UUID entryId;
        try (Connection c = open()) {
            UUID glAccountId = anyGlAccountId(c);
            entryId = insertEntry(c, "DRAFT");
            insertLine(c, entryId, glAccountId, 1, new BigDecimal("50.0000"), BigDecimal.ZERO);
            insertLine(c, entryId, glAccountId, 2, BigDecimal.ZERO, new BigDecimal("10.0000"));
            c.commit();
        }

        try (Connection c = open()) {
            try (PreparedStatement ps =
                    c.prepareStatement("UPDATE journal_entry SET status = 'POSTED' WHERE journal_entry_id = ?")) {
                ps.setObject(1, entryId);
                assertThat(ps.executeUpdate()).isEqualTo(1);
            }
            assertThatThrownBy(c::commit)
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("is POSTED but unbalanced")
                    .hasMessageContaining(entryId.toString());
        }
        assertThat(entryStatus(entryId)).isEqualTo("DRAFT"); // flip rolled back
    }

    /** Intra-transaction rebalance is legal: triggers are deferred, only the commit-time state counts. */
    @Test
    void postedEntry_rebalancedWithinTransaction_commits() throws SQLException {
        UUID entryId;
        try (Connection c = open()) {
            UUID glAccountId = anyGlAccountId(c);
            entryId = insertEntry(c, "POSTED");
            insertLine(c, entryId, glAccountId, 1, new BigDecimal("75.0000"), BigDecimal.ZERO);
            // Transiently unbalanced here; balancing credit added before commit.
            insertLine(c, entryId, glAccountId, 2, BigDecimal.ZERO, new BigDecimal("75.0000"));
            c.commit();
        }
        assertThat(entryStatus(entryId)).isEqualTo("POSTED");
    }

    /** Per-line CHECK: a line may not carry both a debit and a credit. Zero/zero rows stay legal. */
    @Test
    void lineWithBothDebitAndCredit_rejectedByCheckConstraint() throws SQLException {
        try (Connection c = open()) {
            UUID glAccountId = anyGlAccountId(c);
            UUID entryId = insertEntry(c, "DRAFT");
            assertThatThrownBy(() -> insertLine(
                            c, entryId, glAccountId, 1, new BigDecimal("10.0000"), new BigDecimal("10.0000")))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("chk_journal_entry_line_debit_xor_credit");
            c.rollback();
        }

        // Legacy default rows (0/0) remain legal.
        UUID entryId;
        try (Connection c = open()) {
            UUID glAccountId = anyGlAccountId(c);
            entryId = insertEntry(c, "DRAFT");
            insertLine(c, entryId, glAccountId, 1, BigDecimal.ZERO, BigDecimal.ZERO);
            c.commit();
        }
        assertThat(entryStatus(entryId)).isEqualTo("DRAFT");
    }

    // ---------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------

    private static Connection open() throws SQLException {
        Connection c =
                DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        c.setAutoCommit(false);
        return c;
    }

    private static UUID anyGlAccountId(Connection c) throws SQLException {
        try (PreparedStatement ps =
                        c.prepareStatement("SELECT gl_account_id FROM gl_account ORDER BY account_code LIMIT 1");
                ResultSet rs = ps.executeQuery()) {
            assertThat(rs.next()).as("seed gl_account rows present").isTrue();
            return rs.getObject(1, UUID.class);
        }
    }

    private static UUID insertEntry(Connection c, String status) throws SQLException {
        UUID entryId = UUID.randomUUID();
        try (PreparedStatement ps =
                c.prepareStatement("INSERT INTO journal_entry (journal_entry_id, status, entry_type, transaction_date,"
                        + " is_balanced, total_debits, total_credits, created_at, modified_at,"
                        + " created_by, modified_by)"
                        + " VALUES (?, ?, 'MANUAL', now(), true, 0, 0, now(), now(), 'a1-it', 'a1-it')")) {
            ps.setObject(1, entryId);
            ps.setString(2, status);
            ps.executeUpdate();
        }
        return entryId;
    }

    private static void insertLine(
            Connection c, UUID entryId, UUID glAccountId, int lineNumber, BigDecimal debit, BigDecimal credit)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO journal_entry_line (line_id, journal_entry_id, gl_account_id, line_number,"
                        + " debit_amount, credit_amount) VALUES (?, ?, ?, ?, ?, ?)")) {
            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, entryId);
            ps.setObject(3, glAccountId);
            ps.setInt(4, lineNumber);
            ps.setBigDecimal(5, debit);
            ps.setBigDecimal(6, credit);
            ps.executeUpdate();
        }
    }

    private static String entryStatus(UUID entryId) throws SQLException {
        try (Connection c = open();
                PreparedStatement ps =
                        c.prepareStatement("SELECT status FROM journal_entry WHERE journal_entry_id = ?")) {
            ps.setObject(1, entryId);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as("journal_entry row present").isTrue();
                return rs.getString(1);
            }
        }
    }

    private static int unbalancedPostedEntryCount() throws SQLException {
        try (Connection c = open();
                PreparedStatement ps =
                        c.prepareStatement("SELECT count(*) FROM journal_entry je JOIN journal_entry_line l"
                                + " ON l.journal_entry_id = je.journal_entry_id"
                                + " WHERE je.status = 'POSTED' GROUP BY je.journal_entry_id"
                                + " HAVING abs(sum(l.debit_amount) - sum(l.credit_amount)) > 0.0001")) {
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }
}
