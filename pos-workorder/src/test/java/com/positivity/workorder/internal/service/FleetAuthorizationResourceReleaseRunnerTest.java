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
import com.positivity.workorder.service.TechnicianAssignmentService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
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

    @BeforeEach
    void setUp() {
        runner = new FleetAuthorizationResourceReleaseRunner(
                authorizationRepository,
                technicianAssignmentService,
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofHours(4),
                50);
        when(authorizationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("an overdue blocked authorization releases its assignment and is marked released")
    void overdueBlockedAuthorizationIsReleased() {
        WorkorderFleetAuthorization authorization =
                row(FleetAuthorizationStatus.PENDING, NOW.minus(Duration.ofHours(5)));
        when(authorizationRepository.findDueForResourceRelease(any(), any(), any()))
                .thenReturn(List.of(authorization));

        runner.releaseOverdue();

        verify(technicianAssignmentService).releaseAssignment(eq(WORKORDER_ID), any(), any());
        assertThat(authorization.getResourcesReleasedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("an authorization granted between the query and the write is not released")
    void grantedBetweenQueryAndWriteIsNotReleased() {
        // The query result is stale by the time the write runs. Re-checking inside the write is
        // what stops a resource being pulled from a job that is now cleared to start.
        WorkorderFleetAuthorization authorization =
                row(FleetAuthorizationStatus.PENDING, NOW.minus(Duration.ofHours(5)));
        when(authorizationRepository.findDueForResourceRelease(any(), any(), any()))
                .thenReturn(List.of(authorization));
        authorization.setStatus(FleetAuthorizationStatus.GRANTED);

        runner.releaseOverdue();

        verify(technicianAssignmentService, never()).releaseAssignment(any(), any(), any());
        assertThat(authorization.getResourcesReleasedAt()).isNull();
    }

    @Test
    @DisplayName("an authorization already released is not released again")
    void alreadyReleasedIsNotReleasedAgain() {
        WorkorderFleetAuthorization authorization =
                row(FleetAuthorizationStatus.PENDING, NOW.minus(Duration.ofHours(5)));
        authorization.setResourcesReleasedAt(NOW.minus(Duration.ofMinutes(30)));
        when(authorizationRepository.findDueForResourceRelease(any(), any(), any()))
                .thenReturn(List.of(authorization));

        runner.releaseOne(authorization);

        verify(technicianAssignmentService, never()).releaseAssignment(any(), any(), any());
    }

    @Test
    @DisplayName("one failing release does not stop the rest of the batch")
    void oneFailureDoesNotStopTheBatch() {
        WorkorderFleetAuthorization first = row(FleetAuthorizationStatus.PENDING, NOW.minus(Duration.ofHours(5)));
        WorkorderFleetAuthorization second = row(FleetAuthorizationStatus.REFUSED, NOW.minus(Duration.ofHours(6)));
        second.setWorkorderId(UUID.fromString("019200aa-0000-7000-8000-0000000000c2"));
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
