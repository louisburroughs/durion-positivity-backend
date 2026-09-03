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
import java.util.HashMap;
import java.util.Map;
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
 * Proves the {@code V33__accounting_event_reference.sql} backfill (issue
 * #1680): existing {@code accounting_event} rows are numbered {@code
 * AE-{YYYYMM}-{n}} by {@code received_at} ascending within each month, and
 * the matching {@code accounting_sequence} rows are seeded/advanced so
 * runtime assignment ({@code EventIngestionServiceImpl.assignEventReference})
 * cannot collide with a backfilled reference.
 *
 * <p>Two-stage, same pattern as {@link AccountingPeriodBackfillIT}: stage 1
 * migrates only the versioned {@code V1..V32} scripts (copied into a
 * {@code @TempDir} to skip the repeatable seed, which needs later-migration
 * columns), seeds {@code accounting_event} rows with direct SQL across two
 * months, then stage 2 migrates the real classpath chain to latest so V33
 * runs its backfill against genuine historical data.
 *
 * <p>Skipped cleanly when Docker is unavailable ({@code disabledWithoutDocker}).
 */
@Testcontainers(disabledWithoutDocker = true)
class AccountingEventReferenceBackfillIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @Test
    void v33Backfill_numbersExistingEventsByReceivedAtAndSeedsSequence(@TempDir Path stage1Migrations)
            throws SQLException, IOException, URISyntaxException {
        // Stage 1: migrate up to and including V32 — the schema state V33 saw in production.
        copyVersionedMigrationsThrough(stage1Migrations, 32);
        stage1Flyway(stage1Migrations).migrate();

        // Seed accounting_event rows: two in September 2026 (received_at ascending order
        // must decide the -1/-2 suffix), one in October 2026 (its own scope, starting at -1).
        UUID sepFirst;
        UUID sepSecond;
        UUID octFirst;
        try (Connection c = open()) {
            sepFirst = insertEvent(c, Instant.parse("2026-09-05T10:00:00Z"));
            sepSecond = insertEvent(c, Instant.parse("2026-09-20T08:00:00Z"));
            octFirst = insertEvent(c, Instant.parse("2026-10-01T00:00:00Z"));
            c.commit();
        }

        // Stage 2: migrate to latest — runs the V33 backfill against the seeded rows.
        latestFlyway().migrate();

        Map<UUID, String> references = fetchReferences();
        assertThat(references.get(sepFirst)).isEqualTo("AE-202609-1");
        assertThat(references.get(sepSecond)).isEqualTo("AE-202609-2");
        assertThat(references.get(octFirst)).isEqualTo("AE-202610-1");

        // Each backfilled reference is unique (uq_accounting_event_event_reference holds).
        assertThat(references.values()).doesNotHaveDuplicates();

        // accounting_sequence seeded so the *next* runtime assignment in each scope
        // continues past the backfilled max instead of colliding with it.
        assertThat(sequenceNextValue("AE-202609")).isEqualTo(3L);
        assertThat(sequenceNextValue("AE-202610")).isEqualTo(2L);
    }

    // ---------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------

    private static Flyway stage1Flyway(Path migrationsDir) {
        return Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("filesystem:" + migrationsDir)
                .target("32")
                .load();
    }

    private static Flyway latestFlyway() {
        return Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load();
    }

    private static void copyVersionedMigrationsThrough(Path targetDir, int maxVersion)
            throws IOException, URISyntaxException {
        Pattern versioned = Pattern.compile("^V(\\d+)__.+\\.sql$");
        Path source = Path.of(AccountingEventReferenceBackfillIT.class
                .getResource("/db/migration")
                .toURI());
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

    private static UUID insertEvent(Connection c, Instant receivedAt) throws SQLException {
        UUID eventId = UUID.randomUUID();
        try (PreparedStatement ps =
                c.prepareStatement("INSERT INTO accounting_event (event_id, version, status, event_type, source_system,"
                        + " transaction_date, payload, received_at)"
                        + " VALUES (?, 0, 'RECEIVED', 'SALE', 'POS_ACCOUNTING_API', ?, '{}'::jsonb, ?)")) {
            ps.setObject(1, eventId);
            ps.setTimestamp(2, Timestamp.from(receivedAt));
            ps.setTimestamp(3, Timestamp.from(receivedAt));
            ps.executeUpdate();
        }
        return eventId;
    }

    private static Map<UUID, String> fetchReferences() throws SQLException {
        Map<UUID, String> references = new HashMap<>();
        try (Connection c = open();
                PreparedStatement ps = c.prepareStatement("SELECT event_id, event_reference FROM accounting_event");
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                references.put(rs.getObject("event_id", UUID.class), rs.getString("event_reference"));
            }
        }
        return references;
    }

    private static long sequenceNextValue(String scopeKey) throws SQLException {
        try (Connection c = open();
                PreparedStatement ps =
                        c.prepareStatement("SELECT next_value FROM accounting_sequence WHERE scope_key = ?")) {
            ps.setString(1, scopeKey);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next())
                        .as("accounting_sequence row for scope %s", scopeKey)
                        .isTrue();
                return rs.getLong("next_value");
            }
        }
    }
}
