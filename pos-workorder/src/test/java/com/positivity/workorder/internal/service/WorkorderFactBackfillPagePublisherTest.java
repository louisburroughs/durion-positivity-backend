package com.positivity.workorder.internal.service;

import static com.positivity.tenancy.testing.TenantTestSupport.TENANT_A;
import static com.positivity.tenancy.testing.TenantTestSupport.asTenant;
import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.workorder.internal.config.OutboxEventWriter;
import com.positivity.workorder.internal.entity.OutboxEvent;
import com.positivity.workorder.internal.entity.Workorder;
import com.positivity.workorder.internal.enums.WorkorderStatus;
import com.positivity.workorder.internal.repository.OutboxEventRepository;
import com.positivity.workorder.internal.repository.WorkorderRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Integration test for {@link WorkorderFactBackfillPagePublisher} (issue #2021 AC8): proves the
 * backfill forces a fresh snapshot from live entity state — not a re-send of stale outbox bytes —
 * and that not dirtying the row republishes at the same {@code aggregateVersion}.
 *
 * <p>Each test runs its own transaction pair ({@code NOT_SUPPORTED} on the test method,
 * {@code REQUIRES_NEW} on {@link WorkorderFactBackfillPagePublisher#publishPage}), mirroring {@code
 * OutboxReplayServiceImplIntegrationTest}'s platform fan-out tests: the page publisher commits its
 * own transaction independently of any test-managed one, which is exactly the behaviour under test.
 */
@DataJpaTest(properties = {"workorder.kafka.enabled=true", "spring.flyway.enabled=false"})
@Import({OutboxEventWriter.class, WorkorderFactPublisher.class, WorkorderFactBackfillPagePublisher.class})
class WorkorderFactBackfillPagePublisherTest {

    @TestConfiguration
    static class Config {
        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-09-10T12:00:00Z"), ZoneOffset.UTC);
        }

        @Bean
        ObjectMapper objectMapper() {
            return JsonMapper.builder().build();
        }
    }

    @Autowired
    private WorkorderRepository workorderRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private WorkorderFactBackfillPagePublisher pagePublisher;

    @AfterEach
    void cleanUp() {
        asTenant(TENANT_A, () -> {
            outboxEventRepository.deleteAll();
            workorderRepository.deleteAll();
        });
    }

    /**
     * {@code createdAt}/{@code updatedAt} are {@code @CreatedDate}-managed, and JPA auditing is not
     * reliably on in this slice's context (see {@code WorkorderOpenResourceHolderQueryTest}), so they
     * are stamped explicitly rather than left to it.
     */
    private Workorder startedWorkorder(String number, Instant startedAt, Instant completedAt) {
        return asTenant(
                TENANT_A,
                () -> workorderRepository.save(Workorder.builder()
                        .workorderNumber(number)
                        .status(completedAt != null ? WorkorderStatus.COMPLETED : WorkorderStatus.WORK_IN_PROGRESS)
                        .workStartedAt(startedAt)
                        .completedAt(completedAt)
                        .createdAt(startedAt)
                        .updatedAt(startedAt)
                        .build()));
    }

    private Workorder neverStartedWorkorder(String number) {
        return asTenant(
                TENANT_A,
                () -> workorderRepository.save(Workorder.builder()
                        .workorderNumber(number)
                        .status(WorkorderStatus.DRAFT)
                        .createdAt(Instant.parse("2026-09-09T08:00:00Z"))
                        .updatedAt(Instant.parse("2026-09-09T08:00:00Z"))
                        .build()));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("#2021 the backfill publishes a fresh snapshot carrying workStartedAt")
    void backfillPublishesFreshSnapshotWithActualTimes() {
        Workorder workorder = startedWorkorder("WO-2021-0001", Instant.parse("2026-09-09T08:00:00Z"), null);
        // No outbox row exists yet for this workorder -- exactly the state a workorder that started
        // before #2021 shipped would be in: outbox replay has nothing to re-send.
        assertThat(outboxEventRepository.findAll()).isEmpty();

        List<UUID> published = asTenant(TENANT_A, () -> pagePublisher.publishPage(null, 10));

        assertThat(published).containsExactly(workorder.getId());
        List<OutboxEvent> rows = outboxEventRepository.findAll();
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().getPayload())
                .contains("\"workStartedAt\":\"2026-09-09T08:00:00Z\"")
                .contains("\"completedAt\":null");
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("#2021 a never-started workorder is excluded from the backfill selection")
    void neverStartedWorkorderIsExcluded() {
        neverStartedWorkorder("WO-2021-0002");

        List<UUID> published = asTenant(TENANT_A, () -> pagePublisher.publishPage(null, 10));

        assertThat(published).isEmpty();
        assertThat(outboxEventRepository.findAll()).isEmpty();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("#2021 the backfill pages by keyset id, bounded to the requested page size")
    void backfillPagesByKeysetIdBoundedToPageSize() {
        Workorder first = startedWorkorder("WO-2021-0003", Instant.parse("2026-09-09T08:00:00Z"), null);
        Workorder second = startedWorkorder("WO-2021-0004", Instant.parse("2026-09-09T09:00:00Z"), null);
        List<UUID> ordered =
                Stream.of(first, second).map(Workorder::getId).sorted().toList();

        List<UUID> firstPage = asTenant(TENANT_A, () -> pagePublisher.publishPage(null, 1));

        assertThat(firstPage).containsExactly(ordered.get(0));

        List<UUID> secondPage = asTenant(TENANT_A, () -> pagePublisher.publishPage(firstPage.get(0), 1));

        assertThat(secondPage).containsExactly(ordered.get(1));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("#2021 republishing without dirtying the row keeps the same aggregateVersion")
    void republishKeepsSameAggregateVersion() {
        Workorder workorder = startedWorkorder("WO-2021-0005", Instant.parse("2026-09-09T08:00:00Z"), null);
        long versionBefore = workorder.getVersion();

        asTenant(TENANT_A, () -> pagePublisher.publishPage(null, 10));
        asTenant(TENANT_A, () -> pagePublisher.publishPage(null, 10));

        long versionAfter = asTenant(
                        TENANT_A,
                        () -> workorderRepository.findById(workorder.getId()).orElseThrow())
                .getVersion();
        assertThat(versionAfter)
                .as("the backfill only reads the row; it must never dirty it")
                .isEqualTo(versionBefore);

        List<OutboxEvent> rows = outboxEventRepository.findAll();
        assertThat(rows).hasSize(2);
        // Same aggregateVersion on both facts -- intended, not a bug to "fix": the replica's stale
        // guard is strictly-below, so re-applying an equal version is a safe no-op.
        String expectedVersionField = "\"aggregateVersion\":" + versionBefore;
        assertThat(rows).allSatisfy(row -> assertThat(row.getPayload()).contains(expectedVersionField));
    }
}
