package com.positivity.supplier.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.supplier.TestClockConfig;
import com.positivity.supplier.internal.config.JpaConfig;
import com.positivity.supplier.internal.domain.model.ProtocolFamily;
import com.positivity.supplier.internal.domain.model.SupplierCapability;
import com.positivity.supplier.internal.entity.SupplierEndpointBindingEntity;
import com.positivity.supplier.internal.entity.SupplierProfileEntity;
import com.positivity.supplier.internal.entity.SupplierScheduleLeaseEntity;
import com.positivity.supplier.internal.enums.ProfileSourceOfTruth;
import com.positivity.supplier.internal.repository.SupplierEndpointBindingRepository;
import com.positivity.supplier.internal.repository.SupplierProfileRepository;
import com.positivity.supplier.internal.repository.SupplierScheduleLeaseRepository;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The lease protocol as the scheduler uses it (binding decision 4).
 *
 * <p>The load-bearing test here is {@link #aStolenLeaseRollsBackThePageAlongWithItsCheckpoint}: a
 * checkpoint that can commit independently of its page is how a window gets silently skipped, and no
 * error surfaces when it happens. Everything else in this class is scaffolding around proving that.
 */
@DataJpaTest(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:pos_supplier_coord;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
            "spring.jpa.hibernate.ddl-auto=validate"
        })
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaConfig.class, TestClockConfig.class, SupplierScheduleCoordinator.class})
// Test-managed transactions OFF, and this is a requirement of what is under test rather than a preference.
// The coordinator's claim/heartbeat/stillOwns/release each run REQUIRES_NEW, so inside @DataJpaTest's own
// uncommitted transaction they would open a separate transaction that CANNOT SEE the lease fixture and would
// match zero rows -- every test would fail for a reason that has nothing to do with lease semantics. Fixtures
// here therefore commit (each repository call is its own transaction) and are cleaned up over plain JDBC.
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SupplierScheduleCoordinatorTest {

    private static final Instant WINDOW_END = Instant.parse("2026-08-11T03:00:00Z");

    @Autowired
    private SupplierScheduleCoordinator coordinator;

    @Autowired
    private SupplierScheduleLeaseRepository leaseRepository;

    @Autowired
    private SupplierProfileRepository profileRepository;

    @Autowired
    private SupplierEndpointBindingRepository bindingRepository;

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
        binding.setScheduleCron("0 0 3 * * *");
        bindingId = bindingRepository.saveAndFlush(binding).getId();

        leaseRepository.saveAndFlush(SupplierScheduleLeaseEntity.builder()
                .bindingId(bindingId)
                .vendorProfileId(profileId)
                .capability(SupplierCapability.PRICE_CATALOG)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build());
    }

    @AfterEach
    void cleanUp() throws Exception {
        // No enclosing transaction to roll back, so the rows are really there. Deleted in FK order.
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM supplier_schedule_lease");
            statement.executeUpdate("DELETE FROM supplier_endpoint_binding");
            statement.executeUpdate("DELETE FROM supplier_profile");
        }
    }

    /** Forces expiry using DB time, mirroring how time actually passes in production. */
    private void expireLease() {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            // owner_token is deliberately LEFT SET. chk_slease_claim_consistent requires owner_token and
            // leased_until to be both null or both populated, so nulling one models a state the schema
            // forbids -- and an expired-but-still-attributed lease is what really happens when a run stalls.
            int updated = statement.executeUpdate(
                    "UPDATE supplier_schedule_lease SET leased_until = now() - INTERVAL '1' SECOND"
                            + " WHERE binding_id = '" + bindingId + "'");
            if (updated != 1) {
                throw new AssertionError("expireLease matched " + updated + " rows; the fixture is not there");
            }
        } catch (java.sql.SQLException ex) {
            throw new AssertionError("could not expire the lease", ex);
        }
    }

    // ── Claim and release ───────────────────────────────────────────────────────────

    @Test
    void oneRunClaimsAndAnotherIsRefused() {
        String runA = SupplierScheduleCoordinator.newOwnerToken();
        String runB = SupplierScheduleCoordinator.newOwnerToken();

        assertThat(coordinator.tryClaim(bindingId, runA)).isTrue();
        assertThat(coordinator.tryClaim(bindingId, runB))
                .as("every instance but one must be refused; that is the normal outcome, not an error")
                .isFalse();
    }

    @Test
    void releaseIsANoOpForARunThatDoesNotHoldTheLease() {
        String runA = SupplierScheduleCoordinator.newOwnerToken();
        String runB = SupplierScheduleCoordinator.newOwnerToken();
        coordinator.tryClaim(bindingId, runA);

        // Safe to call unconditionally in a finally block: it must not release someone else's claim.
        coordinator.release(bindingId, runB, "OK");

        assertThat(coordinator.stillOwns(bindingId, runA))
                .as("A still owns the lease after B's spurious release")
                .isTrue();
    }

    @Test
    void ownerTokensAreUniquePerRunSoTwoRunsOnOneInstanceAreDistinguishable() {
        assertThat(SupplierScheduleCoordinator.newOwnerToken())
                .isNotEqualTo(SupplierScheduleCoordinator.newOwnerToken());
    }

    // ── Checkpoint discipline ───────────────────────────────────────────────────────

    @Test
    void aSuccessfulPageAdvancesTheCheckpoint() {
        String runA = SupplierScheduleCoordinator.newOwnerToken();
        coordinator.tryClaim(bindingId, runA);

        coordinator.runPage(bindingId, runA, () -> WINDOW_END);

        assertThat(leaseRepository.findById(bindingId).orElseThrow().getCheckpointAt())
                .isEqualTo(WINDOW_END);
    }

    @Test
    void aPageThatThrowsLeavesTheCheckpointWhereItWas() {
        String runA = SupplierScheduleCoordinator.newOwnerToken();
        coordinator.tryClaim(bindingId, runA);

        assertThatThrownBy(() -> coordinator.runPage(bindingId, runA, () -> {
                    throw new IllegalStateException("vendor page failed");
                }))
                .isInstanceOf(IllegalStateException.class);

        assertThat(leaseRepository.findById(bindingId).orElseThrow().getCheckpointAt())
                .as("the checkpoint must not advance past work that did not complete, or the window is skipped")
                .isNull();
    }

    /**
     * THE test. A's lease is taken over mid-run; A's next page must roll back <em>together with</em> its
     * checkpoint. If the checkpoint could commit independently, the window would be marked done while
     * its work was discarded, and nothing would ever reprocess it.
     */
    @Test
    void aStolenLeaseRollsBackThePageAlongWithItsCheckpoint() {
        String runA = SupplierScheduleCoordinator.newOwnerToken();
        String runB = SupplierScheduleCoordinator.newOwnerToken();
        assertThat(coordinator.tryClaim(bindingId, runA)).isTrue();
        expireLease();
        assertThat(coordinator.tryClaim(bindingId, runB)).isTrue();

        AtomicInteger pagesExecuted = new AtomicInteger();
        assertThatThrownBy(() -> coordinator.runPage(bindingId, runA, () -> {
                    pagesExecuted.incrementAndGet();
                    return WINDOW_END;
                }))
                .isInstanceOf(SupplierScheduleCoordinator.LeaseLostException.class)
                .hasMessageContaining("rolled back");

        assertThat(pagesExecuted.get())
                .as("the page ran before the checkpoint was attempted, which is why it must roll back")
                .isEqualTo(1);
        assertThat(leaseRepository.findById(bindingId).orElseThrow().getCheckpointAt())
                .as("a stolen run must not land a checkpoint for a window the new owner also processes")
                .isNull();
    }

    @Test
    void aRunThatLostItsLeaseSeesStillOwnsFalseSoItCanStopBeforeTheNextPage() {
        String runA = SupplierScheduleCoordinator.newOwnerToken();
        String runB = SupplierScheduleCoordinator.newOwnerToken();
        coordinator.tryClaim(bindingId, runA);
        assertThat(coordinator.stillOwns(bindingId, runA)).isTrue();

        expireLease();
        coordinator.tryClaim(bindingId, runB);

        assertThat(coordinator.stillOwns(bindingId, runA))
                .as("checked between pages so a lost lease stops the run cleanly rather than by a failure")
                .isFalse();
    }

    // ── Heartbeat ───────────────────────────────────────────────────────────────────

    @Test
    void theOwnerCanHeartbeatAndALostRunCannot() {
        String runA = SupplierScheduleCoordinator.newOwnerToken();
        String runB = SupplierScheduleCoordinator.newOwnerToken();
        coordinator.tryClaim(bindingId, runA);

        assertThat(coordinator.heartbeat(bindingId, runA)).isTrue();

        expireLease();
        coordinator.tryClaim(bindingId, runB);
        assertThat(coordinator.heartbeat(bindingId, runA))
                .as("a heartbeat is how a run discovers takeover; it must report failure, not extend")
                .isFalse();
    }

    // ── Multi-page runs ─────────────────────────────────────────────────────────────

    @Test
    void successivePagesAdvanceTheCheckpointMonotonically() {
        String runA = SupplierScheduleCoordinator.newOwnerToken();
        coordinator.tryClaim(bindingId, runA);

        coordinator.runPage(bindingId, runA, () -> Instant.parse("2026-08-11T01:00:00Z"));
        coordinator.runPage(bindingId, runA, () -> Instant.parse("2026-08-11T02:00:00Z"));
        coordinator.runPage(bindingId, runA, () -> WINDOW_END);

        assertThat(leaseRepository.findById(bindingId).orElseThrow().getCheckpointAt())
                .isEqualTo(WINDOW_END);
    }

    @Test
    void eachPageRunsUnderItsOwnFreshCorrelationScope() {
        // Issue #1264: scheduled exchanges must trace to a run rather than appearing uncaused. The page
        // is the transactional unit, so its exchanges share one id -- and successive pages get distinct
        // ids, so a reprocessed page after a lost lease is honestly a new correlation.
        String runA = SupplierScheduleCoordinator.newOwnerToken();
        coordinator.tryClaim(bindingId, runA);
        java.util.List<String> pageCorrelations = new java.util.ArrayList<>();

        coordinator.runPage(bindingId, runA, () -> {
            pageCorrelations.add(com.positivity.supplier.internal.audit.SupplierCorrelationContext.current()
                    .orElseThrow());
            return Instant.parse("2026-08-11T01:00:00Z");
        });
        coordinator.runPage(bindingId, runA, () -> {
            pageCorrelations.add(com.positivity.supplier.internal.audit.SupplierCorrelationContext.current()
                    .orElseThrow());
            return WINDOW_END;
        });

        assertThat(pageCorrelations)
                .hasSize(2)
                .doesNotHaveDuplicates()
                .allSatisfy(id -> assertThat(id).isNotBlank());
        assertThat(com.positivity.supplier.internal.audit.SupplierCorrelationContext.current())
                .as("the scope must not leak past the page onto the scheduler thread")
                .isEmpty();
    }

    @Test
    void aPageMustReportTheWindowItCompleted() {
        String runA = SupplierScheduleCoordinator.newOwnerToken();
        coordinator.tryClaim(bindingId, runA);

        // A null window end would silently mean "no checkpoint", which is indistinguishable from a
        // page that did nothing -- so it is rejected rather than accepted.
        assertThatThrownBy(() -> coordinator.runPage(bindingId, runA, () -> null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("window end");
    }

    /**
     * Guards the transaction-boundary decision itself. {@code runPage} must be {@code REQUIRED}, never
     * {@code REQUIRES_NEW}: the audit observer in this same module uses {@code REQUIRES_NEW}, and
     * copying that here would let a checkpoint commit independently of its page.
     */
    @Test
    void runPageJoinsTheCallersTransactionRatherThanStartingItsOwn() throws Exception {
        Transactional annotation = SupplierScheduleCoordinator.class
                .getMethod("runPage", UUID.class, String.class, SupplierScheduleCoordinator.SchedulePage.class)
                .getAnnotation(Transactional.class);

        assertThat(annotation).isNotNull();
        assertThat(annotation.propagation())
                .as("REQUIRES_NEW here would let a checkpoint commit while its page rolled back,"
                        + " silently skipping a window")
                .isEqualTo(Propagation.REQUIRED);
    }

    /**
     * The lease lifecycle operations must each run in their own transaction, and this is pinned structurally
     * because H2 <em>cannot</em> reproduce the hazard: H2's {@code now()} is statement-scoped, while
     * <strong>PostgreSQL's {@code now()} is {@code transaction_timestamp()} and does not advance for the
     * transaction's lifetime</strong>.
     *
     * <p>On PostgreSQL, joining a long page transaction would break each one differently. A heartbeat five
     * minutes into a ten-minute page would compute its new expiry from the moment the page STARTED — so the
     * operation whose entire purpose is keeping a long run's lease alive would compute an expiry already in
     * the past and the lease would be stolen mid-run. {@code stillOwns} would compare against that same frozen
     * reading and report an expired lease as live. {@code release} would roll back with a failed page, holding
     * the lease until natural expiry — the one case where prompt release matters most.
     *
     * <p>Every behavioural test of these passes on H2 either way, which is exactly why the annotation is what
     * gets asserted. {@code clock_timestamp()} was rejected as the alternative fix: it is PostgreSQL-only, so
     * every contention test that establishes this lease's correctness would stop running, and it would not fix
     * the release-rollback half at all.
     *
     * <p>{@code runPage} is asserted to be {@code REQUIRED}, because binding decision 4 requires the
     * checkpoint to commit with the page it describes; a checkpoint committing independently would mark a
     * rolled-back window processed and skip it forever.
     */
    @Test
    void leaseLifecycleOperationsRunInTheirOwnTransaction() throws Exception {
        for (String method : new String[] {"tryClaim", "heartbeat", "stillOwns", "release"}) {
            assertThat(propagationOf(method))
                    .as(
                            "%s must be REQUIRES_NEW: PostgreSQL now() is frozen for the transaction's lifetime,"
                                    + " so joining a long page transaction makes its time reading wrong",
                            method)
                    .isEqualTo(Propagation.REQUIRES_NEW);
        }

        assertThat(propagationOf("runPage"))
                .as("runPage must stay REQUIRED so the checkpoint commits with its page (binding decision 4)")
                .isEqualTo(Propagation.REQUIRED);
    }

    private static Propagation propagationOf(String methodName) {
        return java.util.Arrays.stream(SupplierScheduleCoordinator.class.getDeclaredMethods())
                .filter(m -> m.getName().equals(methodName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no such coordinator method: " + methodName))
                .getAnnotation(org.springframework.transaction.annotation.Transactional.class)
                .propagation();
    }
}
