package com.positivity.workorder.internal.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.tenancy.TenantContext;
import com.positivity.workorder.PostgresSliceTestBase;
import com.positivity.workorder.WorkorderPostgresContainer;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The two partial unique indexes V3 adds, against the real PostgreSQL schema (#1984, #1985).
 *
 * <p>These rules were previously enforced only by a read-then-write in Java, which two concurrent
 * requests pass together: neither sees the other's uncommitted row, so both "free" answers are
 * correct when read and both writes land. A mock cannot show that, and neither can a test that runs
 * both writes in one transaction — the whole point is what happens across two. So the assertions
 * here are made through raw connections taken from the pool, each its own transaction, exactly as
 * two simultaneous requests would be.
 *
 * <p>The tests deliberately do <em>not</em> go through the service: the service's own checks are
 * covered by {@code ServicePositionServiceImplTest} and {@code TechnicianAssignmentServiceImplTest},
 * and what is in question here is whether the database still refuses when those checks are bypassed
 * — which is the only version of the rule that holds under concurrency.
 *
 * <p>Requires Docker.
 */
@DisplayName("Assignment uniqueness constraints (V3)")
class AssignmentConstraintTest extends PostgresSliceTestBase {

    private static final String UNIQUE_VIOLATION = "23505";

    private static final UUID SITE = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4401");
    private static final UUID OTHER_SITE = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4402");
    private static final UUID BAY = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4403");
    private static final UUID MOBILE_UNIT = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4404");
    private static final UUID TECHNICIAN = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4405");
    private static final UUID OTHER_TECHNICIAN = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4406");

    private static final Timestamp NOW = Timestamp.from(Instant.parse("2026-03-10T09:00:00Z"));

    /** Every workorder these tests create, cleaned up by id because the writes really commit. */
    private final List<UUID> createdWorkorders = new ArrayList<>();

    @Autowired
    private DataSource dataSource;

    @AfterEach
    void deleteCommittedRows() throws SQLException {
        // The slice's own transaction rolls back, but these rows were committed on their own
        // connections and would otherwise leak into the next test in this container.
        try (Connection connection =
                        WorkorderPostgresContainer.ownerDataSource().getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("DELETE FROM service_position_assignment WHERE TRUE");
            statement.execute("DELETE FROM technician_assignment WHERE TRUE");
            statement.execute("DELETE FROM workorder WHERE TRUE");
        }
        createdWorkorders.clear();
    }

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    /** A committed open workorder at {@link #SITE}, holding no position. */
    private UUID givenOpenWorkorder(String status) throws SQLException {
        UUID id = UUID.randomUUID();
        try (Connection connection = connection();
                PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO workorder (tenant_id, id, workorder_number, version, created_at, updated_at,
                                               location_id, shop_id, status, is_reopened)
                        VALUES (?, ?, ?, 0, ?, ?, ?, ?, ?, FALSE)
                        """)) {
            statement.setObject(1, TENANT);
            statement.setObject(2, id);
            statement.setString(3, "WO-" + id.toString().substring(0, 8));
            statement.setTimestamp(4, NOW);
            statement.setTimestamp(5, NOW);
            statement.setObject(6, SITE);
            statement.setObject(7, SITE);
            statement.setString(8, status);
            statement.executeUpdate();
        }
        createdWorkorders.add(id);
        return id;
    }

    /** Place a workorder on a position on its own connection and transaction. */
    private void place(UUID workorderId, String resourceType, UUID resourceId) throws SQLException {
        try (Connection connection = connection()) {
            place(connection, workorderId, resourceType, resourceId);
        }
    }

    private void place(Connection connection, UUID workorderId, String resourceType, UUID resourceId)
            throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement("UPDATE workorder SET resource_type = ?, resource_id = ? WHERE id = ?")) {
            statement.setString(1, resourceType);
            statement.setObject(2, resourceId);
            statement.setObject(3, workorderId);
            statement.executeUpdate();
        }
    }

    private void setStatus(UUID workorderId, String status, boolean reopened) throws SQLException {
        try (Connection connection = connection();
                PreparedStatement statement =
                        connection.prepareStatement("UPDATE workorder SET status = ?, is_reopened = ? WHERE id = ?")) {
            statement.setString(1, status);
            statement.setBoolean(2, reopened);
            statement.setObject(3, workorderId);
            statement.executeUpdate();
        }
    }

    private void assignTechnician(UUID workorderId, UUID technicianId, boolean current, Timestamp assignedAt)
            throws SQLException {
        try (Connection connection = connection();
                PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO technician_assignment (tenant_id, workorder_id, technician_id, assigned_at,
                                                           assigned_by, current, created_at, updated_at)
                        VALUES (?, ?, ?, ?, 'dispatch', ?, ?, ?)
                        """)) {
            statement.setObject(1, TENANT);
            statement.setObject(2, workorderId);
            statement.setObject(3, technicianId);
            statement.setTimestamp(4, assignedAt);
            statement.setBoolean(5, current);
            statement.setTimestamp(6, NOW);
            statement.setTimestamp(7, NOW);
            statement.executeUpdate();
        }
    }

    /** A pooled connection; {@code TenantAwareDataSource} binds the tenant on checkout. */
    private Connection connection() throws SQLException {
        return dataSource.getConnection();
    }

    private long countHoldingPosition(UUID resourceId) throws SQLException {
        try (Connection connection = connection();
                PreparedStatement statement =
                        connection.prepareStatement("SELECT count(*) FROM workorder WHERE resource_id = ?")) {
            statement.setObject(1, resourceId);
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    @Nested
    @DisplayName("workorder_open_position_uniq")
    class OpenPositionIndex {

        @Test
        @DisplayName("#1984: a second open workorder cannot take a bay that is already held")
        void bayHoldsOneOpenWorkorder() throws SQLException {
            UUID first = givenOpenWorkorder("WORK_IN_PROGRESS");
            UUID second = givenOpenWorkorder("APPROVED");
            place(first, "BAY", BAY);

            assertThatThrownBy(() -> place(second, "BAY", BAY))
                    .isInstanceOf(SQLException.class)
                    .satisfies(
                            ex -> assertThat(((SQLException) ex).getSQLState()).isEqualTo(UNIQUE_VIOLATION));
        }

        @Test
        @DisplayName("#1984: a mobile unit is exclusive on the same terms as a bay")
        void mobileUnitHoldsOneOpenWorkorder() throws SQLException {
            UUID first = givenOpenWorkorder("ASSIGNED");
            UUID second = givenOpenWorkorder("ASSIGNED");
            place(first, "MOBILE_UNIT", MOBILE_UNIT);

            assertThatThrownBy(() -> place(second, "MOBILE_UNIT", MOBILE_UNIT))
                    .isInstanceOf(SQLException.class)
                    .satisfies(
                            ex -> assertThat(((SQLException) ex).getSQLState()).isEqualTo(UNIQUE_VIOLATION));
        }

        @Test
        @DisplayName("#1984: two concurrent assigns to one free bay — exactly one wins")
        void concurrentAssignsLeaveExactlyOneWinner() throws Exception {
            UUID first = givenOpenWorkorder("APPROVED");
            UUID second = givenOpenWorkorder("APPROVED");

            ExecutorService executor = Executors.newSingleThreadExecutor();
            try (Connection winner = connection()) {
                winner.setAutoCommit(false);
                place(winner, first, "BAY", BAY);

                // The loser's UPDATE blocks on the winner's uncommitted row rather than failing
                // immediately — which is precisely why an application-level "is the bay free?" check
                // cannot decide this. It unblocks when the winner commits, and only then is refused.
                CountDownLatch started = new CountDownLatch(1);
                Future<SQLException> loser = executor.submit(() -> {
                    // The tenant binding is a ThreadLocal, and TenantAwareDataSource stamps it onto
                    // the connection at checkout — so a worker thread that does not bind it gets a
                    // connection with no app.current_tenant, and row-level security then makes this
                    // UPDATE match zero rows and succeed, instead of the index refusing it. Binding
                    // here is what makes the second request a real competitor for the bay rather
                    // than a no-op that quietly proves nothing.
                    TenantContext.bind(TENANT);
                    try (Connection connection = connection()) {
                        connection.setAutoCommit(false);
                        started.countDown();
                        place(connection, second, "BAY", BAY);
                        connection.commit();
                        return null;
                    } catch (SQLException e) {
                        return e;
                    } finally {
                        TenantContext.clear();
                    }
                });

                assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
                // Give the loser time to reach the lock before the winner commits; if it has not, the
                // winner simply commits first anyway and the loser is refused on the committed row.
                Thread.sleep(300);
                winner.commit();

                SQLException failure = loser.get(30, TimeUnit.SECONDS);
                // Cast to Throwable: SQLException is itself an Iterable<Throwable>, so the bare
                // assertThat overload is ambiguous.
                assertThat((Throwable) failure).isNotNull();
                assertThat(failure.getSQLState()).isEqualTo(UNIQUE_VIOLATION);
            } finally {
                executor.shutdownNow();
            }

            assertThat(countHoldingPosition(BAY)).isEqualTo(1);
        }

        @Test
        @DisplayName("#1984: a hold position takes any number of open workorders")
        void holdHasNoCapacityLimit() throws SQLException {
            for (int i = 0; i < 3; i++) {
                place(givenOpenWorkorder("AWAITING_PARTS"), "HOLD", SITE);
            }

            assertThat(countHoldingPosition(SITE)).isEqualTo(3);
        }

        @Test
        @DisplayName("#1984: an unset position is always allowed, however many workorders are unplaced")
        void unsetIsAlwaysAllowed() throws SQLException {
            givenOpenWorkorder("DRAFT");
            givenOpenWorkorder("DRAFT");
            givenOpenWorkorder("APPROVED");

            assertThat(countHoldingPosition(BAY)).isZero();
        }

        @Test
        @DisplayName("#1984: completing or cancelling a workorder frees its bay for the next one")
        void closingAWorkorderFreesTheBay() throws SQLException {
            UUID first = givenOpenWorkorder("WORK_IN_PROGRESS");
            UUID second = givenOpenWorkorder("APPROVED");
            place(first, "BAY", BAY);

            // The application clears resource_id on close; this asserts the index's own predicate, so
            // the stale claim is deliberately left in place — a closed workorder must not hold a bay
            // even when its id is still on the row.
            setStatus(first, "COMPLETED", false);

            place(second, "BAY", BAY);
            assertThat(countHoldingPosition(BAY)).isEqualTo(2);
        }

        @Test
        @DisplayName("#1984: a reopened COMPLETED workorder is open again and keeps its bay")
        void reopenedWorkorderStillHoldsTheBay() throws SQLException {
            UUID first = givenOpenWorkorder("WORK_IN_PROGRESS");
            UUID second = givenOpenWorkorder("APPROVED");
            place(first, "BAY", BAY);
            setStatus(first, "COMPLETED", true);

            // Reopening never changes the status, so a plain NOT IN (COMPLETED, CANCELLED) predicate
            // would free a bay somebody is still working in. The index has to mirror isLocked().
            assertThatThrownBy(() -> place(second, "BAY", BAY))
                    .isInstanceOf(SQLException.class)
                    .satisfies(
                            ex -> assertThat(((SQLException) ex).getSQLState()).isEqualTo(UNIQUE_VIOLATION));
        }

        @Test
        @DisplayName("#1984: the same bay id at two sites is still one position, and still exclusive")
        void theIndexIsKeyedOnThePositionNotTheSite() throws SQLException {
            UUID first = givenOpenWorkorder("ASSIGNED");
            UUID second = givenOpenWorkorder("ASSIGNED");
            place(first, "BAY", BAY);
            try (Connection connection = connection();
                    PreparedStatement statement =
                            connection.prepareStatement("UPDATE workorder SET location_id = ? WHERE id = ?")) {
                statement.setObject(1, OTHER_SITE);
                statement.setObject(2, second);
                statement.executeUpdate();
            }

            // A bay id is globally unique (UUID v7), so moving the workorder to another site does not
            // make the bay a different position. The service refuses this earlier, with a 422.
            assertThatThrownBy(() -> place(second, "BAY", BAY)).isInstanceOf(SQLException.class);
        }
    }

    @Nested
    @DisplayName("technician_assignment_one_current_uniq")
    class SingleCurrentTechnicianIndex {

        @Test
        @DisplayName("#1985: a workorder cannot have two current technicians")
        void oneCurrentTechnician() throws SQLException {
            UUID workorderId = givenOpenWorkorder("ASSIGNED");
            assignTechnician(workorderId, TECHNICIAN, true, NOW);

            assertThatThrownBy(() -> assignTechnician(workorderId, OTHER_TECHNICIAN, true, NOW))
                    .isInstanceOf(SQLException.class)
                    .satisfies(
                            ex -> assertThat(((SQLException) ex).getSQLState()).isEqualTo(UNIQUE_VIOLATION));
        }

        @Test
        @DisplayName("#1985: history is unconstrained — any number of closed rows may sit under the current one")
        void closedRowsAreUnconstrained() throws SQLException {
            UUID workorderId = givenOpenWorkorder("WORK_IN_PROGRESS");
            assignTechnician(
                    workorderId,
                    TECHNICIAN,
                    false,
                    Timestamp.from(NOW.toInstant().minusSeconds(7200)));
            assignTechnician(
                    workorderId,
                    OTHER_TECHNICIAN,
                    false,
                    Timestamp.from(NOW.toInstant().minusSeconds(3600)));
            assignTechnician(workorderId, TECHNICIAN, true, NOW);

            try (Connection connection = connection();
                    PreparedStatement statement = connection.prepareStatement(
                            "SELECT count(*) FROM technician_assignment WHERE workorder_id = ?")) {
                statement.setObject(1, workorderId);
                try (ResultSet rs = statement.executeQuery()) {
                    rs.next();
                    assertThat(rs.getLong(1)).isEqualTo(3);
                }
            }
        }

        @Test
        @DisplayName("#1985: two concurrent assigns to one unassigned workorder — exactly one wins")
        void concurrentAssignsLeaveExactlyOneCurrent() throws Exception {
            UUID workorderId = givenOpenWorkorder("APPROVED");

            ExecutorService executor = Executors.newSingleThreadExecutor();
            try (Connection winner = connection()) {
                winner.setAutoCommit(false);
                try (PreparedStatement statement = winner.prepareStatement("""
                        INSERT INTO technician_assignment (tenant_id, workorder_id, technician_id, assigned_at,
                                                           assigned_by, current, created_at, updated_at)
                        VALUES (?, ?, ?, ?, 'dispatch', TRUE, ?, ?)
                        """)) {
                    statement.setObject(1, TENANT);
                    statement.setObject(2, workorderId);
                    statement.setObject(3, TECHNICIAN);
                    statement.setTimestamp(4, NOW);
                    statement.setTimestamp(5, NOW);
                    statement.setTimestamp(6, NOW);
                    statement.executeUpdate();
                }

                CountDownLatch started = new CountDownLatch(1);
                Future<SQLException> loser = executor.submit(() -> {
                    // Bind the tenant on this thread too — see the note in the position race. Without
                    // it the insert is refused by row-level security (SQLSTATE 42501) rather than by
                    // technician_assignment_one_current_uniq, which would pass a "there was an
                    // exception" assertion while proving nothing about the index.
                    TenantContext.bind(TENANT);
                    try {
                        started.countDown();
                        assignTechnician(workorderId, OTHER_TECHNICIAN, true, NOW);
                        return null;
                    } catch (SQLException e) {
                        return e;
                    } finally {
                        TenantContext.clear();
                    }
                });

                assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
                Thread.sleep(300);
                winner.commit();

                SQLException failure = loser.get(30, TimeUnit.SECONDS);
                // Cast to Throwable: SQLException is itself an Iterable<Throwable>, so the bare
                // assertThat overload is ambiguous.
                assertThat((Throwable) failure).isNotNull();
                assertThat(failure.getSQLState()).isEqualTo(UNIQUE_VIOLATION);
            } finally {
                executor.shutdownNow();
            }

            try (Connection connection = connection();
                    PreparedStatement statement = connection.prepareStatement(
                            "SELECT technician_id FROM technician_assignment WHERE workorder_id = ? AND current")) {
                statement.setObject(1, workorderId);
                try (ResultSet rs = statement.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getObject(1, UUID.class)).isEqualTo(TECHNICIAN);
                    assertThat(rs.next()).isFalse();
                }
            }
        }
    }

    @Nested
    @DisplayName("the migration's cleanup of data that predates the constraints")
    class MigrationCleanup {

        /**
         * The reconciliation block, read out of the shipped migration rather than restated here.
         *
         * <p>A test that retyped the SQL would prove only that the copy works. This runs the exact
         * text Flyway runs, so a change to the migration that breaks the reconciliation breaks these
         * tests.
         *
         * <p>Whole block, in file order, rather than statements picked out individually. The order
         * <em>is</em> the behaviour under test — type the untyped placements, release the closed
         * ones, seed history, then park duplicates — and an earlier version that selected statements
         * by shape got it wrong twice: first by assuming there were exactly two, then by matching a
         * table name that every statement's CTE happens to read from. Taking the block entire
         * removes the guessing.
         *
         * @param fromMarker the section comment the block starts at
         * @param toMarker   the line the block ends before, normally the index this data must satisfy
         */
        private String reconciliationBlock(String fromMarker, String toMarker) throws IOException {
            String migration = readMigration();
            int from = migration.indexOf(fromMarker);
            int to = migration.indexOf(toMarker, from);
            assertThat(from).as("start marker %s in V3", fromMarker).isNotNegative();
            assertThat(to)
                    .as("end marker %s after %s in V3", toMarker, fromMarker)
                    .isGreaterThan(from);
            return migration.substring(from, to);
        }

        private String readMigration() throws IOException {
            try (InputStream in = getClass()
                    .getClassLoader()
                    .getResourceAsStream("db/migration/V3__service_position_and_single_technician.sql")) {
                assertThat(in).as("V3 migration on the test classpath").isNotNull();
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }

        /**
         * Runs a statement as the owner with the occupancy index dropped, so the pre-migration data
         * the cleanup exists for can actually be created, then puts the index back. Dropping and
         * recreating needs ownership, which the application role does not have — that is exactly the
         * arrangement alpha runs, where Flyway is the owner and the pool is not.
         */
        private void withIndexDropped(String indexName, String createIndexSql, SqlWork work) throws Exception {
            try (Connection owner = WorkorderPostgresContainer.ownerDataSource().getConnection();
                    Statement statement = owner.createStatement()) {
                statement.execute("DROP INDEX " + indexName);
                try {
                    work.run(owner);
                } finally {
                    statement.execute(createIndexSql);
                }
            }
        }

        @Test
        @DisplayName("#1984: a bay held by several open workorders keeps the earliest and parks the rest")
        void duplicatePositionsAreParked() throws Exception {
            UUID keeper = givenOpenWorkorder("WORK_IN_PROGRESS");
            UUID loser = givenOpenWorkorder("APPROVED");
            String reconciliation = reconciliationBlock(
                    "-- 3. Normalise the placements that predate the constraints.",
                    "CREATE UNIQUE INDEX workorder_open_position_uniq");

            withIndexDropped("workorder_open_position_uniq", """
                    CREATE UNIQUE INDEX workorder_open_position_uniq
                        ON public.workorder (tenant_id, resource_type, resource_id)
                        WHERE resource_id IS NOT NULL
                          AND resource_type IN ('BAY', 'MOBILE_UNIT')
                          AND (status IS NULL OR status <> 'CANCELLED')
                          AND (status IS NULL OR status <> 'COMPLETED' OR is_reopened IS TRUE)
                    """, owner -> {
                try (Statement statement = owner.createStatement()) {
                    // Two open workorders on one bay, which nothing prevented before this migration.
                    // The later-created one is the loser; note it is left untyped, so this also
                    // covers the legacy placement the reconciliation has to type before it can see it.
                    statement.execute("UPDATE workorder SET resource_type = 'BAY', resource_id = '" + BAY
                            + "', created_at = '2026-03-01T00:00:00Z' WHERE id = '" + keeper + "'");
                    statement.execute("UPDATE workorder SET resource_type = NULL, resource_id = '" + BAY
                            + "', created_at = '2026-03-02T00:00:00Z' WHERE id = '" + loser + "'");
                    statement.execute(reconciliation);
                }
            });

            assertThat(positionOf(keeper)).isEqualTo(new Position("BAY", BAY));
            // Moved to the site's lot, not deleted and not silently unplaced: the vehicle is somewhere.
            assertThat(positionOf(loser)).isEqualTo(new Position("HOLD", SITE));

            // The bay claim survives the move. Overwriting the placement before recording it would
            // lose the only evidence of where this job actually was.
            assertThat(placementsOf(loser))
                    .hasSize(2)
                    .anySatisfy(placement -> {
                        assertThat(placement.resourceType()).isEqualTo("BAY");
                        assertThat(placement.resourceId()).isEqualTo(BAY);
                        assertThat(placement.current()).isFalse();
                        assertThat(placement.releasedBy()).isEqualTo("system:migration");
                    })
                    .anySatisfy(placement -> {
                        assertThat(placement.resourceType()).isEqualTo("HOLD");
                        assertThat(placement.resourceId()).isEqualTo(SITE);
                        assertThat(placement.current()).isTrue();
                    });

            // The keeper is untouched: one row, still current, still the bay.
            assertThat(placementsOf(keeper)).singleElement().satisfies(placement -> {
                assertThat(placement.resourceType()).isEqualTo("BAY");
                assertThat(placement.current()).isTrue();
            });
        }

        @Test
        @DisplayName("#1985: a workorder with several current technicians keeps the latest assigned")
        void duplicateCurrentTechniciansAreClosed() throws Exception {
            UUID workorderId = givenOpenWorkorder("WORK_IN_PROGRESS");
            // From the cleanup itself, not the section header: the header is followed by the
            // released_by ALTER, which Flyway runs once and a replay here would fail on.
            String reconciliation = reconciliationBlock(
                    "-- assignTechnician closed the previous row",
                    "CREATE UNIQUE INDEX technician_assignment_one_current_uniq");

            withIndexDropped("technician_assignment_one_current_uniq", """
                    CREATE UNIQUE INDEX technician_assignment_one_current_uniq
                        ON public.technician_assignment (tenant_id, workorder_id)
                        WHERE current
                    """, owner -> {
                try (Statement statement = owner.createStatement()) {
                    for (Object[] row : new Object[][] {
                        {OTHER_TECHNICIAN, "2026-03-01 08:00:00"}, {TECHNICIAN, "2026-03-01 11:00:00"}
                    }) {
                        statement.execute("""
                                        INSERT INTO technician_assignment (tenant_id, workorder_id, technician_id,
                                                assigned_at, assigned_by, current, created_at, updated_at)
                                        VALUES ('"""
                                + TENANT + "', '" + workorderId + "', '" + row[0] + "', '" + row[1]
                                + "', 'dispatch', TRUE, now(), now())");
                    }
                    statement.execute(reconciliation);
                }
            });

            try (Connection connection = connection();
                    PreparedStatement statement = connection.prepareStatement(
                            "SELECT technician_id FROM technician_assignment WHERE workorder_id = ? AND current")) {
                statement.setObject(1, workorderId);
                try (ResultSet rs = statement.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    // Latest assigned_at wins: it is the most recent statement of intent.
                    assertThat(rs.getObject(1, UUID.class)).isEqualTo(TECHNICIAN);
                    assertThat(rs.next()).isFalse();
                }
            }

            try (Connection connection = connection();
                    PreparedStatement statement = connection.prepareStatement("""
                            SELECT count(*) FROM technician_assignment
                            WHERE workorder_id = ? AND NOT current AND unassigned_at IS NOT NULL
                              AND reassignment_reason IS NOT NULL
                            """)) {
                statement.setObject(1, workorderId);
                try (ResultSet rs = statement.executeQuery()) {
                    rs.next();
                    // Closed, never deleted: the history is append-only.
                    assertThat(rs.getLong(1)).isEqualTo(1);
                }
            }
        }

        /** Every service-position history row for a workorder, oldest first. */
        private List<Placement> placementsOf(UUID workorderId) throws SQLException {
            try (Connection connection = connection();
                    PreparedStatement statement = connection.prepareStatement("""
                            SELECT resource_type, resource_id, current, released_by
                            FROM service_position_assignment
                            WHERE workorder_id = ?
                            ORDER BY id
                            """)) {
                statement.setObject(1, workorderId);
                try (ResultSet rs = statement.executeQuery()) {
                    List<Placement> placements = new ArrayList<>();
                    while (rs.next()) {
                        placements.add(new Placement(
                                rs.getString(1), rs.getObject(2, UUID.class), rs.getBoolean(3), rs.getString(4)));
                    }
                    return placements;
                }
            }
        }

        private Position positionOf(UUID workorderId) throws SQLException {
            try (Connection connection = connection();
                    PreparedStatement statement = connection.prepareStatement(
                            "SELECT resource_type, resource_id FROM workorder WHERE id = ?")) {
                statement.setObject(1, workorderId);
                try (ResultSet rs = statement.executeQuery()) {
                    rs.next();
                    return new Position(rs.getString(1), rs.getObject(2, UUID.class));
                }
            }
        }
    }

    private record Position(String resourceType, UUID resourceId) {}

    private record Placement(String resourceType, UUID resourceId, boolean current, String releasedBy) {}

    @FunctionalInterface
    private interface SqlWork {
        void run(Connection owner) throws Exception;
    }
}
