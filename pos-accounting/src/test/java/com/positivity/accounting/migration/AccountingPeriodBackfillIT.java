package com.positivity.accounting.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * PR #970 review F7: automated assertion for the {@code V9__accounting_period.sql}
 * backfill, which inserts one OPEN accounting_period row per distinct month with
 * journal_entry activity and mints each period_id with an inline SQL UUID v7 recipe
 * (PostgreSQL 16 has no built-in v7 generator).
 *
 * <p><b>Path taken: full-fidelity staged migration.</b> Unlike
 * {@link JournalEntryBalanceTriggerIT}, which migrates the whole chain in a
 * {@code BeforeAll} (so V9 runs against an empty journal_entry table and backfills
 * nothing), this IT owns its container and drives Flyway explicitly in two stages:
 * first to schema version 8 (the state V9 saw in production), then direct SQL seeds
 * journal_entry rows across two months, then a second migrate to latest runs
 * V9..Vn against real historical data. That exercises the exact production shape
 * of the backfill — the month-bucketing SELECT, the ON CONFLICT guard, and the
 * UUID v7 SQL recipe — rather than testing the recipe expression in isolation.
 *
 * <p><b>Stage 1 is repeatable-free (PRCR-101).</b> A plain
 * {@code target("8")} against {@code classpath:db/migration} would also apply the
 * repeatable {@code R__seed_reference_accounting.sql} in the same run (Flyway
 * applies pending repeatables even under a versioned target), and that seed writes
 * gl_account columns that only exist after V11 — stage 1 would fail before any
 * assertion. So stage 1 copies only the versioned {@code V1__..V8__} scripts from
 * the classpath migration directory into a JUnit {@code @TempDir} and migrates from
 * {@code filesystem:} there; stage 2 migrates from the classpath as usual. The
 * copied scripts are byte-identical to the classpath ones, so stage 2's checksum
 * validation passes and V9..Vn plus the repeatable seed apply normally.
 *
 * <p>Assertions: exactly one OPEN period per active month (none for inactive
 * months), correct period_code and month boundary dates, and each backfilled
 * period_id is a well-formed UUID v7 (version nibble 7, RFC 4122 variant, and a
 * 48-bit ms-epoch timestamp prefix consistent with the migration wall clock).
 *
 * <p>Skipped cleanly when Docker is unavailable ({@code disabledWithoutDocker}).
 */
@Testcontainers(disabledWithoutDocker = true)
class AccountingPeriodBackfillIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    /** Clock-skew allowance around the stage-2 migration window, in minutes. */
    private static final long CLOCK_SKEW_MINUTES = 5;

    @Test
    void v9Backfill_createsOneOpenPeriodPerActiveMonth_withUuidV7Ids(@TempDir Path stage1Migrations)
            throws SQLException, IOException, URISyntaxException {
        // Stage 1: migrate up to and including V8 — the schema state V9 saw in production.
        // Repeatable-free: only V1..V8 scripts are staged into the temp dir (see class javadoc).
        copyVersionedMigrationsThrough(stage1Migrations, 8);
        stage1Flyway(stage1Migrations).migrate();

        // Seed journal entries: two in 2025-03 (same month must dedupe to one period),
        // one in 2025-04. No other months have activity.
        try (Connection c = open()) {
            insertDraftEntry(c, LocalDateTime.of(2025, 3, 15, 10, 30));
            insertDraftEntry(c, LocalDateTime.of(2025, 3, 20, 0, 0));
            insertDraftEntry(c, LocalDateTime.of(2025, 4, 1, 0, 0));
            c.commit();
        }

        // Stage 2: migrate to latest — runs the V9 backfill against the seeded rows.
        Instant beforeMigrate = Instant.now().minus(CLOCK_SKEW_MINUTES, ChronoUnit.MINUTES);
        latestFlyway().migrate();
        Instant afterMigrate = Instant.now().plus(CLOCK_SKEW_MINUTES, ChronoUnit.MINUTES);

        List<PeriodRow> periods = fetchPeriods();

        // Exactly one period per active month; no rows for months without activity.
        assertThat(periods).extracting(PeriodRow::periodCode).containsExactly("2025-03", "2025-04");

        PeriodRow march = periods.get(0);
        assertThat(march.startDate()).isEqualTo(LocalDate.of(2025, 3, 1));
        assertThat(march.endDate()).isEqualTo(LocalDate.of(2025, 3, 31));

        PeriodRow april = periods.get(1);
        assertThat(april.startDate()).isEqualTo(LocalDate.of(2025, 4, 1));
        assertThat(april.endDate()).isEqualTo(LocalDate.of(2025, 4, 30));

        for (PeriodRow period : periods) {
            assertThat(period.status())
                    .as("backfilled period %s status", period.periodCode())
                    .isEqualTo("OPEN");
            assertThat(period.createdBy()).isEqualTo("SYSTEM");
            assertUuidV7(period.periodId(), period.periodCode(), beforeMigrate, afterMigrate);
        }
    }

    /**
     * Asserts the id is a well-formed UUID v7: version nibble 7, RFC 4122 variant
     * (10xx — first hex digit of the clock-seq octet in {8, 9, a, b}), and the
     * leading 48 bits decode to a unix-epoch-millisecond timestamp taken during
     * the stage-2 migration window.
     */
    private static void assertUuidV7(UUID id, String periodCode, Instant notBefore, Instant notAfter) {
        assertThat(id.version())
                .as("period %s id %s version nibble", periodCode, id)
                .isEqualTo(7);

        char variantNibble = id.toString().charAt(19);
        assertThat(variantNibble)
                .as("period %s id %s variant nibble", periodCode, id)
                .isIn('8', '9', 'a', 'b');

        long timestampMillis = id.getMostSignificantBits() >>> 16;
        assertThat(Instant.ofEpochMilli(timestampMillis))
                .as("period %s id %s embedded ms-epoch timestamp", periodCode, id)
                .isBetween(notBefore, notAfter);
    }

    // ---------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------

    /** Stage 1: only the staged versioned V1..V8 scripts, no repeatable seed. */
    private static Flyway stage1Flyway(Path migrationsDir) {
        return Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("filesystem:" + migrationsDir)
                .target("8")
                .load();
    }

    /** Stage 2: the real classpath migration chain, exactly as production runs it. */
    private static Flyway latestFlyway() {
        return Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load();
    }

    /**
     * Copies the versioned {@code V<n>__*.sql} scripts with {@code n <= maxVersion}
     * from the classpath migration directory into {@code targetDir}, skipping
     * repeatable ({@code R__*}) and higher-versioned scripts.
     */
    private static void copyVersionedMigrationsThrough(Path targetDir, int maxVersion)
            throws IOException, URISyntaxException {
        Pattern versioned = Pattern.compile("^V(\\d+)__.+\\.sql$");
        Path source = Path.of(
                AccountingPeriodBackfillIT.class.getResource("/db/migration").toURI());
        try (Stream<Path> files = Files.list(source)) {
            for (Path file : files.toList()) {
                String name = file.getFileName().toString();
                Matcher matcher = versioned.matcher(name);
                if (matcher.matches() && Integer.parseInt(matcher.group(1)) <= maxVersion) {
                    Files.copy(file, targetDir.resolve(name));
                }
            }
        }
    }

    private static Connection open() throws SQLException {
        Connection c =
                DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        c.setAutoCommit(false);
        return c;
    }

    private static void insertDraftEntry(Connection c, LocalDateTime transactionDate) throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement("INSERT INTO journal_entry (journal_entry_id, status, entry_type, transaction_date,"
                        + " is_balanced, total_debits, total_credits, created_at, modified_at,"
                        + " created_by, modified_by)"
                        + " VALUES (?, 'DRAFT', 'MANUAL', ?, true, 0, 0, now(), now(), 'f7-it', 'f7-it')")) {
            ps.setObject(1, UUID.randomUUID());
            ps.setTimestamp(2, Timestamp.valueOf(transactionDate));
            ps.executeUpdate();
        }
    }

    private static List<PeriodRow> fetchPeriods() throws SQLException {
        List<PeriodRow> rows = new ArrayList<>();
        try (Connection c = open();
                PreparedStatement ps =
                        c.prepareStatement("SELECT period_id, period_code, start_date, end_date, status, created_by"
                                + " FROM accounting_period ORDER BY period_code");
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                rows.add(new PeriodRow(
                        rs.getObject("period_id", UUID.class),
                        rs.getString("period_code"),
                        rs.getObject("start_date", LocalDate.class),
                        rs.getObject("end_date", LocalDate.class),
                        rs.getString("status"),
                        rs.getString("created_by")));
            }
        }
        return rows;
    }

    private record PeriodRow(
            UUID periodId,
            String periodCode,
            LocalDate startDate,
            LocalDate endDate,
            String status,
            String createdBy) {}
}
