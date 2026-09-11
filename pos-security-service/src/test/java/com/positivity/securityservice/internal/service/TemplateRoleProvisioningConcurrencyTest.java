package com.positivity.securityservice.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.securityservice.PosSecurityServiceApplication;
import com.positivity.securityservice.internal.dto.RoleCreateRequest;
import com.positivity.securityservice.internal.dto.RoleDto;
import com.positivity.securityservice.internal.repository.RoleRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Reproduces the concurrent-create race {@code provisionTemplateRole} must converge on (ADR-0062
 * §6, plan WS8; Copilot review of PR #1955): several callers race platform bulk-ingest for a role
 * name none of them has seen yet, all pass the check-then-insert read, and every loser hits the
 * {@code roles} unique-name index the read could not see coming.
 *
 * <p>Deliberately a real Spring-managed {@link RoleManagementService} bean over a real H2
 * database rather than a mocked repository. The defect the previous review round left in place
 * (Copilot, PR #1955, {@code RoleManagementServiceImpl} around line 151) was specifically about
 * deferred-flush timing: {@code roleRepository.save} inside a single {@code @Transactional}
 * method does not throw when called — Hibernate enqueues the insert and defers it to commit-time
 * flush, which happens only after that method (and any local {@code catch} inside it) has already
 * returned control to its caller. A Mockito mock that throws synchronously from {@code save()}
 * — what {@code provisionTemplateRole_convergesOnAConcurrentCreateCollision} in {@link
 * RoleManagementServiceImplTest} does — cannot exercise that failure mode at all: it proves the
 * mock behaves as stubbed, nothing about whether the real, transactional-proxy-mediated code
 * converges. Only a real transaction boundary, under real concurrent load, can.
 *
 * <p>Mutation-check: reverting {@code provisionTemplateRole} to a single {@code @Transactional}
 * method with the collision caught inline (the previous round's shape) makes this test fail —
 * {@code future.get()} rethrows a raw {@code DataIntegrityViolationException} for at least one
 * racer instead of every racer converging onto one row.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        classes = PosSecurityServiceApplication.class,
        properties = {"security.jwt.secret=test-jwt-secret-key-01234567890123456789"})
@ActiveProfiles("test")
@DisplayName("provisionTemplateRole under a real concurrent create collision")
class TemplateRoleProvisioningConcurrencyTest {

    private static final int RACERS = 8;

    @MockitoBean
    private TokenRevocationManager tokenRevocationManager;

    @Autowired
    private RoleManagementService roleManagementService;

    @Autowired
    private RoleRepository roleRepository;

    @Test
    @DisplayName("every racer converges onto one row instead of a loser's collision escaping as an internal failure")
    void provisionTemplateRole_convergesConcurrentRacersOntoOneRow() throws Exception {
        String roleName =
                "RACE_ROLE_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        RoleCreateRequest request =
                new RoleCreateRequest(roleName, "Concurrency race fixture", null, null, null, null, null);

        ExecutorService pool = Executors.newFixedThreadPool(RACERS);
        // Every racer parks on `go` right after starting, so the pool releases them together
        // instead of one at a time -- the same TOCTOU window a genuine concurrent bulk-ingest hits
        // between its own check-then-insert read and its write.
        CountDownLatch ready = new CountDownLatch(RACERS);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Callable<RoleDto>> racers = new ArrayList<>();
            for (int i = 0; i < RACERS; i++) {
                racers.add(() -> {
                    ready.countDown();
                    go.await();
                    return roleManagementService.provisionTemplateRole(request);
                });
            }

            List<Future<RoleDto>> futures = new ArrayList<>();
            for (Callable<RoleDto> racer : racers) {
                futures.add(pool.submit(racer));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS))
                    .as("every racer thread reached the starting line")
                    .isTrue();
            go.countDown();

            List<RoleDto> results = new ArrayList<>();
            for (Future<RoleDto> future : futures) {
                // get() rethrows whatever a racer produced. Under the previous round's fix that
                // was, for the race's loser, a raw DataIntegrityViolationException escaping
                // provisionTemplateRole instead of converging -- exactly what this test exists to
                // catch (Finding 1, Copilot review of PR #1955).
                results.add(future.get(10, TimeUnit.SECONDS));
            }

            assertThat(results).as("every racer returns instead of throwing").hasSize(RACERS);
            assertThat(results)
                    .as("every racer's row is marked as the template role")
                    .allSatisfy(dto -> assertThat(dto.getTemplateKey()).isEqualTo(roleName));

            List<UUID> distinctRoleIds =
                    results.stream().map(RoleDto::getId).distinct().collect(Collectors.toList());
            assertThat(distinctRoleIds)
                    .as("every racer converges onto the same row")
                    .hasSize(1);

            long persistedCount = roleRepository.findAll().stream()
                    .filter(role -> roleName.equals(role.getName()))
                    .count();
            assertThat(persistedCount).as("exactly one row survives the race").isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }
}
