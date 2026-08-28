package com.positivity.workorder.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.workorder.internal.entity.WorkorderFleetAuthorization;
import com.positivity.workorder.internal.enums.FleetAuthorizationStatus;
import com.positivity.workorder.internal.repository.WorkorderFleetAuthorizationRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Freeing capacity a blocked job cannot currently use (#1346).
 *
 * <p>The rule that matters is the re-check inside the write: a row selected as overdue may have been
 * granted in the interval between the query and the release, and releasing a resource that belongs
 * to a now-cleared job would be actively wrong, not merely late.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("FleetAuthorizationResourceReleaseRunner — released once, and only while still blocking (#1346)")
class FleetAuthorizationResourceReleaseRunnerTest {

    private static final Instant NOW = Instant.parse("2026-08-17T14:00:00Z");
    private static final UUID WORKORDER_ID = UUID.fromString("019200aa-0000-7000-8000-0000000000c1");

    @Mock
    private WorkorderFleetAuthorizationRepository authorizationRepository;

    @Mock
    private TechnicianAssignmentService technicianAssignmentService;

    private FleetAuthorizationResourceReleaseRunner runner;

    /**
     * Held alongside the runner: the per-row release lives in its own bean so that it gets a real
     * transaction, and the guard tests below drive it directly rather than through a tick.
     */
    private FleetAuthorizationResourceReleaser releaser;

    @BeforeEach
    void setUp() {
        releaser = new FleetAuthorizationResourceReleaser(
                authorizationRepository,
                technicianAssignmentService,
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofHours(4));
        runner = new FleetAuthorizationResourceReleaseRunner(
                authorizationRepository, releaser, Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofHours(4), 50);
        when(authorizationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        // The release re-reads by id inside its own transaction, so the mock has to answer that
        // read. Tests that care about the gap between the sweep's query and the write override this
        // to hand back a different row than the one the query produced.
        when(authorizationRepository.findById(any()))
                .thenAnswer(inv -> Optional.ofNullable(managedRows.get((UUID) inv.getArgument(0))));
    }

    /** What {@code findById} sees, keyed by authorization id — i.e. the current state of the table. */
    private final Map<UUID, WorkorderFleetAuthorization> managedRows = new HashMap<>();

    private WorkorderFleetAuthorization inTable(WorkorderFleetAuthorization row) {
        managedRows.put(row.getWorkorderFleetAuthorizationId(), row);
        return row;
    }

    @Test
    @DisplayName("an overdue blocked authorization releases its assignment and is marked released")
    void overdueBlockedAuthorizationIsReleased() {
        WorkorderFleetAuthorization authorization =
                inTable(row(FleetAuthorizationStatus.PENDING, NOW.minus(Duration.ofHours(5))));
        when(authorizationRepository.findDueForResourceRelease(any(), any(), any()))
                .thenReturn(List.of(authorization));

        runner.releaseOverdue();

        verify(technicianAssignmentService).releaseAssignment(eq(WORKORDER_ID), any(), any());
        assertThat(authorization.getResourcesReleasedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("an authorization granted between the query and the write is not released")
    void grantedBetweenQueryAndWriteIsNotReleased() {
        // Two instances on purpose. `stale` is what the sweep's query produced, before this
        // transaction began; `current` is the row as it now stands in the table. Mutating a single
        // shared instance would prove nothing — it passes whether or not the write re-reads, which
        // is how this test previously gave cover to a guard that only ever saw the snapshot.
        WorkorderFleetAuthorization stale = row(FleetAuthorizationStatus.PENDING, NOW.minus(Duration.ofHours(5)));
        WorkorderFleetAuthorization current =
                inTable(row(FleetAuthorizationStatus.GRANTED, NOW.minus(Duration.ofHours(5))));
        when(authorizationRepository.findDueForResourceRelease(any(), any(), any()))
                .thenReturn(List.of(stale));

        runner.releaseOverdue();

        verify(technicianAssignmentService, never()).releaseAssignment(any(), any(), any());
        assertThat(current.getResourcesReleasedAt()).isNull();
        // And the stale snapshot must not have been merged back over the granted row.
        verify(authorizationRepository, never()).save(any());
    }

    @Test
    @DisplayName("an authorization already released is not released again")
    void alreadyReleasedIsNotReleasedAgain() {
        WorkorderFleetAuthorization authorization =
                inTable(row(FleetAuthorizationStatus.PENDING, NOW.minus(Duration.ofHours(5))));
        authorization.setResourcesReleasedAt(NOW.minus(Duration.ofMinutes(30)));
        when(authorizationRepository.findDueForResourceRelease(any(), any(), any()))
                .thenReturn(List.of(authorization));

        releaser.releaseOne(authorization);

        verify(technicianAssignmentService, never()).releaseAssignment(any(), any(), any());
    }

    @Test
    @DisplayName("one failing release does not stop the rest of the batch")
    void oneFailureDoesNotStopTheBatch() {
        WorkorderFleetAuthorization first =
                inTable(row(FleetAuthorizationStatus.PENDING, NOW.minus(Duration.ofHours(5))));
        WorkorderFleetAuthorization second = row(FleetAuthorizationStatus.REFUSED, NOW.minus(Duration.ofHours(6)));
        second.setWorkorderFleetAuthorizationId(UUID.fromString("019200aa-0000-7000-8000-0000000000a2"));
        second.setWorkorderId(UUID.fromString("019200aa-0000-7000-8000-0000000000c2"));
        inTable(second);
        when(authorizationRepository.findDueForResourceRelease(any(), any(), any()))
                .thenReturn(List.of(first, second));
        when(technicianAssignmentService.releaseAssignment(eq(WORKORDER_ID), any(), any()))
                .thenThrow(new RuntimeException("boom"));

        runner.releaseOverdue();

        verify(technicianAssignmentService).releaseAssignment(eq(second.getWorkorderId()), any(), any());
        assertThat(second.getResourcesReleasedAt()).isEqualTo(NOW);
    }

    private static WorkorderFleetAuthorization row(FleetAuthorizationStatus status, Instant firstBlockedAt) {
        return WorkorderFleetAuthorization.builder()
                .workorderFleetAuthorizationId(UUID.fromString("019200aa-0000-7000-8000-0000000000a1"))
                .workorderId(WORKORDER_ID)
                .supplierRef("michelin-de")
                .status(status)
                .firstBlockedAt(firstBlockedAt)
                .build();
    }
}
