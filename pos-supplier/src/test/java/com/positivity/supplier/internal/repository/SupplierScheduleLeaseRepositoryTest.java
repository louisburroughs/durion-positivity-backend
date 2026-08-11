package com.positivity.supplier.internal.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.supplier.internal.config.JpaConfig;
import com.positivity.supplier.internal.domain.model.ProtocolFamily;
import com.positivity.supplier.internal.domain.model.SupplierCapability;
import com.positivity.supplier.internal.entity.SupplierEndpointBindingEntity;
import com.positivity.supplier.internal.entity.SupplierProfileEntity;
import com.positivity.supplier.internal.entity.SupplierScheduleLeaseEntity;
import com.positivity.supplier.internal.enums.ProfileSourceOfTruth;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;

/**
 * Lease correctness under contention (binding decision 4).
 *
 * <p>These run against a real H2 database with <strong>real concurrent threads on their own JDBC
 * connections</strong>, not a mocked repository. A lease is a claim about what happens when two
 * instances race; asserting it against a mock proves only that a method was called. Every interesting
 * assertion here is "exactly one winner" or "the loser cannot write".
 *
 * <p>Time is never supplied by the test either: expiry is forced with SQL-computed timestamps, so the
 * tests exercise the same database-time authority production relies on.
 */
@DataJpaTest(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:pos_supplier_lease;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
            "spring.jpa.hibernate.ddl-auto=validate",
            // 16 contenders each hold a connection while racing, plus the test's own. Without an
            // explicit ceiling the pool default can be smaller, and threads then block acquiring a
            // connection instead of racing -- which made a deliberately broken claim fail on the
            // readiness latch rather than on the winner count, hiding what the test proves.
            "spring.datasource.hikari.maximum-pool-size=24"
        })
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaConfig.class)
class SupplierScheduleLeaseRepositoryTest {

    private static final String OWNER_A = "run-A";
    private static final String OWNER_B = "run-B";

    @Autowired
    private SupplierScheduleLeaseRepository leaseRepository;

    @Autowired
    private SupplierProfileRepository profileRepository;

    @Autowired
    private SupplierEndpointBindingRepository bindingRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private DataSource dataSource;

    private UUID bindingId;

    @BeforeEach
    void setUp() {
        SupplierProfileEntity profile = new SupplierProfileEntity();
        profile.setSupplierRef("michelin-" + UUID.randomUUID());
        profile.setDisplayName("Michelin");
        profile.setEnabled(true);
        profile.setSourceOfTruth(ProfileSourceOfTruth.ADMIN);
        UUID profileId = profileRepository.saveAndFlush(profile).getVendorProfileId();

        SupplierEndpointBindingEntity binding = new SupplierEndpointBindingEntity();
        binding.setVendorProfileId(profileId);
        binding.setCapability(SupplierCapability.PRICE_CATALOG);
        binding.setProtocolFamily(ProtocolFamily.EDIWHEEL_A25);
        binding.setProtocolVersion("A2_5");
        binding.setBaseUrl("https://vendor.test");
        binding.setPath("/pricat");
        binding.setAuthConfigName("auth");
        binding.setEnabled(true);
        bindingId = bindingRepository.saveAndFlush(binding).getId();

        leaseRepository.saveAndFlush(SupplierScheduleLeaseEntity.builder()
                .bindingId(bindingId)
                .vendorProfileId(profileId)
                .capability(SupplierCapability.PRICE_CATALOG)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                // Deliberately NOT setting version: a non-null @Version makes Hibernate treat the
                // entity as detached and issue an UPDATE, which matches no row and fails with
                // StaleObjectStateException. Leaving it null lets Hibernate INSERT and seed it.
                .build());
        entityManager.clear();
    }

    /** Force expiry using DB time, mirroring how time actually passes in production. */
    private void expireLease() {
        entityManager
                .createNativeQuery("UPDATE supplier_schedule_lease SET leased_until = now() - INTERVAL '1' SECOND"
                        + " WHERE binding_id = ?1")
                .setParameter(1, bindingId)
                .executeUpdate();
        entityManager.flush();
        entityManager.clear();
    }

    // ── Claim ───────────────────────────────────────────────────────────────────────

    @Test
    void aFreshRowWithNullLeaseIsClaimable() {
        // A null leased_until must read as "unheld", never as "held". Treating it as held in one place
        // and expired in another is how a newly scheduled binding becomes permanently unschedulable.
        assertThat(leaseRepository.claim(bindingId, OWNER_A, 60)).isEqualTo(1);
    }

    @Test
    void aHeldLeaseCannotBeClaimedByAnotherRun() {
        assertThat(leaseRepository.claim(bindingId, OWNER_A, 60)).isEqualTo(1);

        assertThat(leaseRepository.claim(bindingId, OWNER_B, 60))
                .as("a live lease must be exclusive")
                .isZero();
    }

    @Test
    void anExpiredLeaseIsClaimableByAnotherRun() {
        leaseRepository.claim(bindingId, OWNER_A, 60);
        expireLease();

        assertThat(leaseRepository.claim(bindingId, OWNER_B, 60)).isEqualTo(1);
    }

    @Test
    void aReleasedLeaseIsImmediatelyClaimable() {
        leaseRepository.claim(bindingId, OWNER_A, 60);

        assertThat(leaseRepository.release(bindingId, OWNER_A, "OK")).isEqualTo(1);
        assertThat(leaseRepository.claim(bindingId, OWNER_B, 60))
                .as("release nulls the lease, and a null lease must be claimable")
                .isEqualTo(1);
    }

    /**
     * The test the whole design exists for: many instances fire one binding's schedule at the same
     * instant and exactly one may win.
     *
     * <p>Its fixture is inserted over a <strong>separate, committed</strong> JDBC connection rather
     * than through the test's {@code EntityManager}. {@code @DataJpaTest} wraps each test in a
     * transaction it never commits, so rows created the usual way are invisible to other connections —
     * every contender would match zero rows and the test would report "0 winners" while appearing to
     * exercise contention. Getting this wrong makes the test pass for the wrong reason in one direction
     * and fail confusingly in the other.
     */
    @Test
    void exactlyOneOfManyConcurrentClaimsWins() throws Exception {
        UUID contestedBinding = insertCommittedLeaseFixture();
        int contenders = 16;
        CountDownLatch ready = new CountDownLatch(contenders);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(contenders);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < contenders; i++) {
                results.add(pool.submit(claimInOwnConnection(contestedBinding, "run-" + i, ready, go)));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();

            int winners = 0;
            for (Future<Integer> result : results) {
                winners += result.get(20, TimeUnit.SECONDS);
            }
            assertThat(winners)
                    .as(
                            "%d contenders must yield exactly 1 winner; a lease that lets two runs win"
                                    + " is not a lease, and 0 winners means the fixture was not visible",
                            contenders)
                    .isEqualTo(1);
        } finally {
            pool.shutdownNow();
            deleteCommittedFixture(contestedBinding);
        }
    }

    /**
     * Creates profile + binding + lease over an autocommitting connection so all contenders see them.
     *
     * @return the binding id whose lease will be contested
     */
    private UUID insertCommittedLeaseFixture() throws java.sql.SQLException {
        UUID profileId = UUID.randomUUID();
        UUID contestedBinding = UUID.randomUUID();
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(true);
            try (var statement = connection.prepareStatement(
                    "INSERT INTO supplier_profile (vendor_profile_id, supplier_ref, display_name, enabled,"
                            + " sandbox, source_of_truth, created_at, updated_at, version)"
                            + " VALUES (?, ?, 'Contested', TRUE, FALSE, 'ADMIN', now(), now(), 0)")) {
                statement.setObject(1, profileId);
                statement.setString(2, "contested-" + profileId);
                statement.executeUpdate();
            }
            try (var statement = connection.prepareStatement(
                    "INSERT INTO supplier_endpoint_binding (id, vendor_profile_id, capability, protocol_family,"
                            + " protocol_version, base_url, path, auth_config_name, enabled, created_at,"
                            + " updated_at, version)"
                            + " VALUES (?, ?, 'PRICE_CATALOG', 'EDIWHEEL_A25', 'A2_5', 'https://vendor.test',"
                            + " '/pricat', 'auth', TRUE, now(), now(), 0)")) {
                statement.setObject(1, contestedBinding);
                statement.setObject(2, profileId);
                statement.executeUpdate();
            }
            try (var statement = connection.prepareStatement(
                    "INSERT INTO supplier_schedule_lease (binding_id, vendor_profile_id, capability,"
                            + " created_at, updated_at, version)"
                            + " VALUES (?, ?, 'PRICE_CATALOG', now(), now(), 0)")) {
                statement.setObject(1, contestedBinding);
                statement.setObject(2, profileId);
                statement.executeUpdate();
            }
        }
        return contestedBinding;
    }

    private void deleteCommittedFixture(UUID contestedBinding) {
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(true);
            try (var statement =
                    connection.prepareStatement("DELETE FROM supplier_schedule_lease WHERE binding_id = ?")) {
                statement.setObject(1, contestedBinding);
                statement.executeUpdate();
            }
        } catch (java.sql.SQLException ignored) {
            // Cleanup only; the in-memory database is discarded after the class anyway.
        }
    }

    /**
     * Each contender takes its own JDBC connection deliberately. The shared test {@code EntityManager}
     * would serialise them, the race would never happen, and the test would pass without proving
     * anything about concurrency.
     */
    private Callable<Integer> claimInOwnConnection(
            UUID targetBinding, String owner, CountDownLatch ready, CountDownLatch go) {
        return () -> {
            try (var connection = dataSource.getConnection();
                    var statement = connection.prepareStatement("UPDATE supplier_schedule_lease SET owner_token = ?,"
                            + " leased_until = now() + CAST(? AS INTEGER) * INTERVAL '1' SECOND,"
                            + " last_heartbeat_at = now(), last_run_started_at = now(),"
                            + " updated_at = now(), version = version + 1"
                            + " WHERE binding_id = ?"
                            + " AND (leased_until IS NULL OR leased_until < now())")) {
                connection.setAutoCommit(true);
                statement.setString(1, owner);
                statement.setInt(2, 60);
                statement.setObject(3, targetBinding);
                ready.countDown();
                go.await(10, TimeUnit.SECONDS);
                return statement.executeUpdate();
            }
        };
    }

    // ── Stolen lease: the loser must not be able to write ───────────────────────────

    /**
     * A's lease expires, B takes over, and A must then be unable to do anything at all: no heartbeat,
     * no checkpoint, no release of B's claim. The owner guard is what makes takeover safe, so it needs
     * its own test rather than only the contested-claim case.
     */
    @Test
    void aRunWhoseLeaseWasStolenCanNeitherHeartbeatNorCheckpointNorRelease() {
        assertThat(leaseRepository.claim(bindingId, OWNER_A, 60)).isEqualTo(1);
        expireLease();
        assertThat(leaseRepository.claim(bindingId, OWNER_B, 60))
                .as("B takes over the expired lease")
                .isEqualTo(1);

        assertThat(leaseRepository.heartbeat(bindingId, OWNER_A, 60))
                .as("A must not resurrect its claim")
                .isZero();
        assertThat(leaseRepository.advanceCheckpoint(bindingId, OWNER_A, Instant.parse("2026-08-11T00:00:00Z")))
                .as("A must not land a checkpoint for a window B now owns")
                .isZero();
        assertThat(leaseRepository.release(bindingId, OWNER_A, "OK"))
                .as("A must not release B's lease")
                .isZero();

        assertThat(leaseRepository.countLiveOwnership(bindingId, OWNER_B))
                .as("B's lease survives every attempt by A")
                .isEqualTo(1);
        assertThat(leaseRepository.countLiveOwnership(bindingId, OWNER_A)).isZero();
    }

    @Test
    void aStolenRunSeesItselfAsNoLongerOwningSoItCanAbortBeforeItsNextPage() {
        leaseRepository.claim(bindingId, OWNER_A, 60);
        assertThat(leaseRepository.countLiveOwnership(bindingId, OWNER_A)).isEqualTo(1);

        expireLease();
        leaseRepository.claim(bindingId, OWNER_B, 60);

        assertThat(leaseRepository.countLiveOwnership(bindingId, OWNER_A))
                .as("this check is how a running job learns to abort before its next page")
                .isZero();
    }

    @Test
    void aRunThatStalledPastItsOwnLeaseCannotHeartbeatItBack() {
        leaseRepository.claim(bindingId, OWNER_A, 60);
        expireLease();

        assertThat(leaseRepository.heartbeat(bindingId, OWNER_A, 60))
                .as("an expired lease is gone even to its former owner: it must be re-claimed, not extended,"
                        + " or a stalled run could revive a claim another run has taken")
                .isZero();
    }

    // ── Heartbeat and checkpoint ────────────────────────────────────────────────────

    @Test
    void theOwnerCanExtendItsLease() {
        leaseRepository.claim(bindingId, OWNER_A, 60);

        assertThat(leaseRepository.heartbeat(bindingId, OWNER_A, 120)).isEqualTo(1);
        assertThat(leaseRepository.countLiveOwnership(bindingId, OWNER_A)).isEqualTo(1);
    }

    @Test
    void theOwnerCanAdvanceItsCheckpoint() {
        leaseRepository.claim(bindingId, OWNER_A, 60);
        Instant checkpoint = Instant.parse("2026-08-11T03:00:00Z");

        assertThat(leaseRepository.advanceCheckpoint(bindingId, OWNER_A, checkpoint))
                .isEqualTo(1);

        entityManager.clear();
        assertThat(leaseRepository.findById(bindingId).orElseThrow().getCheckpointAt())
                .isEqualTo(checkpoint);
    }

    @Test
    void aNonOwnerCannotAdvanceTheCheckpoint() {
        leaseRepository.claim(bindingId, OWNER_A, 60);

        assertThat(leaseRepository.advanceCheckpoint(bindingId, OWNER_B, Instant.parse("2026-08-11T03:00:00Z")))
                .isZero();
    }

    // ── Time authority ──────────────────────────────────────────────────────────────

    /**
     * Expiry must be database-computed. Were it computed in the JVM, a pod with a skewed clock could
     * write a lease every other pod considers already expired, or valid for hours.
     */
    @Test
    void leaseExpiryIsComputedByTheDatabaseNotTheJvm() {
        leaseRepository.claim(bindingId, OWNER_A, 3600);
        entityManager.clear();

        SupplierScheduleLeaseEntity lease = leaseRepository.findById(bindingId).orElseThrow();

        assertThat(lease.getLeasedUntil()).isNotNull();
        assertThat(lease.getLastHeartbeatAt())
                .as("the claim stamps a DB-computed heartbeat too")
                .isNotNull();
        assertThat(lease.getLeasedUntil())
                .as("a 3600s lease must land far in the future, proving SQL interval arithmetic ran")
                .isAfter(Instant.now().plusSeconds(3000));
    }

    @Test
    void releaseClearsOwnershipSoNothingElseIsBlocked() {
        leaseRepository.claim(bindingId, OWNER_A, 60);

        leaseRepository.release(bindingId, OWNER_A, "OK");
        entityManager.clear();

        SupplierScheduleLeaseEntity lease = leaseRepository.findById(bindingId).orElseThrow();
        assertThat(lease.getOwnerToken()).isNull();
        assertThat(lease.getLeasedUntil()).isNull();
        assertThat(lease.getLastRunOutcome()).isEqualTo("OK");
    }
}
