package com.positivity.supplier.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
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
@Import({JpaConfig.class, SupplierScheduleCoordinator.class})
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
    private EntityManager entityManager;

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
        entityManager.clear();
    }

    private void expireLease() {
        entityManager
                .createNativeQuery("UPDATE supplier_schedule_lease SET leased_until = now() - INTERVAL '1' SECOND"
                        + " WHERE binding_id = ?1")
                .setParameter(1, bindingId)
                .executeUpdate();
        entityManager.flush();
        entityManager.clear();
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

        entityManager.clear();
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

        entityManager.clear();
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
        entityManager.clear();
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

        entityManager.clear();
        assertThat(leaseRepository.findById(bindingId).orElseThrow().getCheckpointAt())
                .isEqualTo(WINDOW_END);
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
}
