package com.positivity.customer.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.customer.internal.entity.FollowUpTask;
import com.positivity.customer.internal.entity.ServiceHistory;
import com.positivity.customer.internal.enums.FollowUpStatus;
import com.positivity.customer.internal.enums.FollowUpType;
import com.positivity.customer.internal.repository.FollowUpTaskRepository;
import com.positivity.customer.internal.repository.ServiceHistoryRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;

/**
 * Unit tests for {@link ServiceDueReminderJob} (Story #1153): stale latest completions become
 * exactly one {@code SERVICE_DUE_REMINDER} each, keyed deterministically so reruns and racing
 * instances cannot duplicate a task.
 */
class ServiceDueReminderJobTest {

    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2026-07-20T12:00:00Z"), ZoneOffset.UTC);
    private static final UUID PARTY_ID = UUID.fromString("01980a58-0000-7000-8000-000000000001");
    private static final UUID VEHICLE_ID = UUID.fromString("01980a58-0000-7000-8000-000000000002");
    private static final UUID HISTORY_ID = UUID.fromString("01980a58-0000-7000-8000-000000000003");
    private static final UUID WORKORDER_ID = UUID.fromString("01980a58-0000-7000-8000-000000000004");

    private final ServiceHistoryRepository serviceHistory = mock(ServiceHistoryRepository.class);
    private final FollowUpTaskRepository followUps = mock(FollowUpTaskRepository.class);

    private ServiceDueReminderJob job;

    @BeforeEach
    void setUp() {
        job = new ServiceDueReminderJob(TEST_CLOCK, serviceHistory, followUps);
    }

    private ServiceHistory history(UUID historyId, UUID partyId, UUID vehicleId, Instant completedAt) {
        return ServiceHistory.builder()
                .serviceHistoryId(historyId)
                .partyId(partyId)
                .vehicleId(vehicleId)
                .sourceWorkorderId(WORKORDER_ID)
                .workorderNumber("WO-2026-1001")
                .completedAt(completedAt)
                .sourceEventId("e-1")
                .createdAt(completedAt)
                .build();
    }

    @Test
    @DisplayName("A stale latest completion becomes one SERVICE_DUE_REMINDER with a deterministic key")
    void createsReminder() {
        Instant completedAt = Instant.parse("2026-01-05T10:00:00Z");
        when(serviceHistory.findServiceDueCandidates(any(), anyString(), any(Pageable.class)))
                .thenReturn(List.of(history(HISTORY_ID, PARTY_ID, VEHICLE_ID, completedAt)));

        job.generateReminders();

        ArgumentCaptor<FollowUpTask> saved = ArgumentCaptor.forClass(FollowUpTask.class);
        verify(followUps).save(saved.capture());
        FollowUpTask task = saved.getValue();
        assertThat(task.getType()).isEqualTo(FollowUpType.SERVICE_DUE_REMINDER);
        assertThat(task.getStatus()).isEqualTo(FollowUpStatus.OPEN);
        assertThat(task.getPartyId()).isEqualTo(PARTY_ID);
        assertThat(task.getVehicleId()).isEqualTo(VEHICLE_ID);
        assertThat(task.getDueDate()).isEqualTo(LocalDate.of(2026, 7, 5));
        assertThat(task.getSourceWorkorderId()).isEqualTo(WORKORDER_ID.toString());
        assertThat(task.getSourceEventId()).isEqualTo("service-due:" + HISTORY_ID);
        assertThat(task.getReason()).contains("2026-01-05").contains("WO-2026-1001");
        assertThat(task.getCreatedBy()).isEqualTo("service-due-reminder");
    }

    @Test
    @DisplayName("Candidates are queried with the configured interval as the cutoff")
    void queriesWithConfiguredCutoff() {
        when(serviceHistory.findServiceDueCandidates(any(), anyString(), any(Pageable.class)))
                .thenReturn(List.of());

        job.generateReminders();

        verify(serviceHistory)
                .findServiceDueCandidates(
                        eq(Instant.parse("2026-01-20T12:00:00Z")),
                        eq(ServiceDueReminderJob.SOURCE_PREFIX),
                        any(Pageable.class));
    }

    @Test
    @DisplayName("Two candidates for the same party/vehicle in one run yield a single reminder")
    void dedupesSamePartyAndVehicleWithinRun() {
        Instant completedAt = Instant.parse("2026-01-05T10:00:00Z");
        UUID otherHistoryId = UUID.fromString("01980a58-0000-7000-8000-000000000005");
        when(serviceHistory.findServiceDueCandidates(any(), anyString(), any(Pageable.class)))
                .thenReturn(List.of(
                        history(HISTORY_ID, PARTY_ID, VEHICLE_ID, completedAt),
                        history(otherHistoryId, PARTY_ID, VEHICLE_ID, completedAt)));

        job.generateReminders();

        verify(followUps, times(1)).save(any(FollowUpTask.class));
    }

    @Test
    @DisplayName("Losing the unique-key race on one reminder does not stop the rest of the batch")
    void continuesAfterUniqueKeyRace() {
        Instant completedAt = Instant.parse("2026-01-05T10:00:00Z");
        UUID otherParty = UUID.fromString("01980a58-0000-7000-8000-000000000006");
        UUID otherHistoryId = UUID.fromString("01980a58-0000-7000-8000-000000000007");
        when(serviceHistory.findServiceDueCandidates(any(), anyString(), any(Pageable.class)))
                .thenReturn(List.of(
                        history(HISTORY_ID, PARTY_ID, VEHICLE_ID, completedAt),
                        history(otherHistoryId, otherParty, VEHICLE_ID, completedAt)));
        when(followUps.save(any(FollowUpTask.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate source_event_id"))
                .thenAnswer(invocation -> invocation.getArgument(0));

        job.generateReminders();

        ArgumentCaptor<FollowUpTask> saved = ArgumentCaptor.forClass(FollowUpTask.class);
        verify(followUps, times(2)).save(saved.capture());
        assertThat(saved.getAllValues().get(1).getPartyId()).isEqualTo(otherParty);
    }
}
