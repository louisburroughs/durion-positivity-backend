package com.positivity.workorder.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.workorder.internal.dto.WorkorderLaborEntryResponse;
import com.positivity.workorder.internal.entity.Workorder;
import com.positivity.workorder.internal.entity.WorkorderLaborEntry;
import com.positivity.workorder.internal.entity.WorkorderServiceLine;
import com.positivity.workorder.internal.enums.WorkorderStatus;
import com.positivity.workorder.internal.exception.LaborEntryNotFoundException;
import com.positivity.workorder.internal.exception.WorkorderRequestValidationException;
import com.positivity.workorder.internal.repository.WorkorderLaborEntryRepository;
import com.positivity.workorder.internal.repository.WorkorderRepository;
import com.positivity.workorder.internal.repository.WorkorderServiceRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Labor recording (CAP-005 story #159): a session may only be opened while the workorder is in a
 * labor-eligible status, only one session may be open per service line, and stopping derives
 * hours worked from the elapsed duration unless they are adjusted manually.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("WorkorderLaborServiceImpl")
class WorkorderLaborServiceImplTest {

    private static final UUID WORKORDER_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f1111");
    private static final UUID SERVICE_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f1112");
    private static final UUID TECHNICIAN_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f1113");
    private static final UUID ENTRY_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f1114");
    private static final UUID OTHER_WORKORDER_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f1115");
    private static final Instant NOW = Instant.parse("2026-03-01T12:00:00Z");

    private static final String START_OPERATION = "labor.start";
    private static final String STOP_OPERATION = "labor.stop";
    private static final String ADJUST_OPERATION = "labor.adjust";

    @Mock
    private WorkorderLaborEntryRepository laborRepository;

    @Mock
    private WorkorderRepository workorderRepository;

    @Mock
    private WorkorderServiceRepository workorderServiceRepository;

    @Mock
    private IdempotencyService idempotencyService;

    private WorkorderLaborServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new WorkorderLaborServiceImpl(
                Clock.fixed(NOW, ZoneOffset.UTC),
                laborRepository,
                workorderRepository,
                workorderServiceRepository,
                idempotencyService);

        when(idempotencyService.getExistingLaborEntryId(anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(laborRepository.save(any())).thenAnswer(invocation -> {
            WorkorderLaborEntry entry = invocation.getArgument(0);
            if (entry.getId() == null) {
                entry.setId(ENTRY_ID);
            }
            return entry;
        });
        when(laborRepository.findByWorkorderService_IdAndEndTimeIsNull(SERVICE_ID))
                .thenReturn(Optional.empty());
    }

    private static Workorder workorder(WorkorderStatus status) {
        Workorder workorder = new Workorder();
        workorder.setId(WORKORDER_ID);
        workorder.setStatus(status);
        return workorder;
    }

    private void givenWorkorder(WorkorderStatus status) {
        when(workorderRepository.findById(WORKORDER_ID)).thenReturn(Optional.of(workorder(status)));
    }

    private void givenServiceLine(UUID owningWorkorderId) {
        WorkorderServiceLine line = new WorkorderServiceLine();
        line.setId(SERVICE_ID);
        if (owningWorkorderId != null) {
            Workorder owner = new Workorder();
            owner.setId(owningWorkorderId);
            line.setWorkOrder(owner);
        }
        when(workorderServiceRepository.findById(SERVICE_ID)).thenReturn(Optional.of(line));
    }

    private WorkorderLaborEntry openEntry(LocalDateTime startTime) {
        WorkorderServiceLine line = new WorkorderServiceLine();
        line.setId(SERVICE_ID);
        return WorkorderLaborEntry.builder()
                .id(ENTRY_ID)
                .workorder(workorder(WorkorderStatus.WORK_IN_PROGRESS))
                .workorderService(line)
                .technicianId(TECHNICIAN_ID)
                .startTime(startTime)
                .hoursWorked(BigDecimal.ZERO)
                .createdBy("jane.smith")
                .createdAt(NOW)
                .build();
    }

    @Nested
    @DisplayName("startLaborSession")
    class StartLaborSession {

        @Test
        @DisplayName("opens a session stamped with the clock and zero hours")
        void startsSession() {
            givenWorkorder(WorkorderStatus.WORK_IN_PROGRESS);
            givenServiceLine(WORKORDER_ID);

            WorkorderLaborEntryResponse entry =
                    service.startLaborSession(WORKORDER_ID, SERVICE_ID, TECHNICIAN_ID, "brake job", "jane.smith", null);

            assertThat(entry.getTechnicianId()).isEqualTo(TECHNICIAN_ID);
            assertThat(entry.getStartTime()).isEqualTo(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
            assertThat(entry.getHoursWorked()).isEqualByComparingTo("0");
            assertThat(entry.getNotes()).isEqualTo("brake job");
            assertThat(entry.getCreatedBy()).isEqualTo("jane.smith");
            assertThat(entry.isActive()).isTrue();
        }

        @Test
        @DisplayName("allows every labor-eligible workorder status")
        void allowsEligibleStatuses() {
            givenServiceLine(WORKORDER_ID);
            for (WorkorderStatus status : List.of(
                    WorkorderStatus.ASSIGNED,
                    WorkorderStatus.WORK_IN_PROGRESS,
                    WorkorderStatus.AWAITING_PARTS,
                    WorkorderStatus.AWAITING_APPROVAL)) {
                givenWorkorder(status);

                assertThat(service.startLaborSession(WORKORDER_ID, SERVICE_ID, TECHNICIAN_ID, null, "jane.smith", null))
                        .isNotNull();
            }
        }

        @Test
        @DisplayName("refuses a workorder in a status that does not allow labor")
        void rejectsIneligibleStatus() {
            givenWorkorder(WorkorderStatus.COMPLETED);

            assertThatThrownBy(() -> service.startLaborSession(
                            WORKORDER_ID, SERVICE_ID, TECHNICIAN_ID, null, "jane.smith", null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Cannot start labor on workorder with status COMPLETED");
        }

        @Test
        @DisplayName("refuses a second open session on the same service line")
        void rejectsConcurrentSession() {
            givenWorkorder(WorkorderStatus.WORK_IN_PROGRESS);
            when(laborRepository.findByWorkorderService_IdAndEndTimeIsNull(SERVICE_ID))
                    .thenReturn(Optional.of(openEntry(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC))));

            assertThatThrownBy(() -> service.startLaborSession(
                            WORKORDER_ID, SERVICE_ID, TECHNICIAN_ID, null, "jane.smith", null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Active labor session already exists");
        }

        @Test
        @DisplayName("rejects an unknown workorder")
        void rejectsUnknownWorkorder() {
            when(workorderRepository.findById(WORKORDER_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.startLaborSession(
                            WORKORDER_ID, SERVICE_ID, TECHNICIAN_ID, null, "jane.smith", null))
                    .isInstanceOf(NoSuchElementException.class)
                    .hasMessageContaining("Workorder not found");
        }

        @Test
        @DisplayName("rejects an unknown service line")
        void rejectsUnknownServiceLine() {
            givenWorkorder(WorkorderStatus.WORK_IN_PROGRESS);
            when(workorderServiceRepository.findById(SERVICE_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.startLaborSession(
                            WORKORDER_ID, SERVICE_ID, TECHNICIAN_ID, null, "jane.smith", null))
                    .isInstanceOf(NoSuchElementException.class)
                    .hasMessageContaining("Workorder service not found");
        }

        @Test
        @DisplayName("rejects a service line owned by another workorder")
        void rejectsForeignServiceLine() {
            givenWorkorder(WorkorderStatus.WORK_IN_PROGRESS);
            givenServiceLine(OTHER_WORKORDER_ID);

            assertThatThrownBy(() -> service.startLaborSession(
                            WORKORDER_ID, SERVICE_ID, TECHNICIAN_ID, null, "jane.smith", null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("does not belong to workorder");
        }

        @Test
        @DisplayName("rejects a detached service line")
        void rejectsDetachedServiceLine() {
            givenWorkorder(WorkorderStatus.WORK_IN_PROGRESS);
            givenServiceLine(null);

            assertThatThrownBy(() -> service.startLaborSession(
                            WORKORDER_ID, SERVICE_ID, TECHNICIAN_ID, null, "jane.smith", null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("does not belong to workorder");
        }

        @Test
        @DisplayName("registers the idempotency key under the start operation")
        void registersIdempotencyKey() {
            givenWorkorder(WorkorderStatus.WORK_IN_PROGRESS);
            givenServiceLine(WORKORDER_ID);

            service.startLaborSession(WORKORDER_ID, SERVICE_ID, TECHNICIAN_ID, null, "jane.smith", "start-key");

            verify(idempotencyService).registerLaborKey(START_OPERATION, "start-key", ENTRY_ID);
        }

        @Test
        @DisplayName("replays the recorded entry when the idempotency key was already used")
        void replaysExistingEntry() {
            WorkorderLaborEntry existing = openEntry(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
            when(idempotencyService.getExistingLaborEntryId(START_OPERATION, "start-key"))
                    .thenReturn(Optional.of(ENTRY_ID));
            when(laborRepository.findById(ENTRY_ID)).thenReturn(Optional.of(existing));

            assertThat(service.startLaborSession(
                            WORKORDER_ID, SERVICE_ID, TECHNICIAN_ID, null, "jane.smith", "start-key"))
                    .isEqualTo(WorkorderLaborEntryResponse.fromEntity(existing));
            verify(laborRepository, never()).save(any());
        }

        @Test
        @DisplayName("fails when the replayed entry has vanished")
        void failsWhenReplayedEntryMissing() {
            when(idempotencyService.getExistingLaborEntryId(START_OPERATION, "start-key"))
                    .thenReturn(Optional.of(ENTRY_ID));
            when(laborRepository.findById(ENTRY_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.startLaborSession(
                            WORKORDER_ID, SERVICE_ID, TECHNICIAN_ID, null, "jane.smith", "start-key"))
                    .isInstanceOf(NoSuchElementException.class)
                    .hasMessageContaining("Labor entry not found");
        }

        @Test
        @DisplayName("treats a blank idempotency key as absent")
        void blankKeyIsNotIdempotent() {
            givenWorkorder(WorkorderStatus.WORK_IN_PROGRESS);
            givenServiceLine(WORKORDER_ID);

            service.startLaborSession(WORKORDER_ID, SERVICE_ID, TECHNICIAN_ID, null, "jane.smith", " ");

            verify(idempotencyService, never()).getExistingLaborEntryId(anyString(), anyString());
            verify(idempotencyService, never()).registerLaborKey(anyString(), anyString(), any());
        }
    }

    @Nested
    @DisplayName("stopLaborSession")
    class StopLaborSession {

        @Test
        @DisplayName("closes the session and derives hours from the elapsed duration")
        void stopsSession() {
            LocalDateTime start = LocalDateTime.ofInstant(NOW, ZoneOffset.UTC).minusMinutes(90);
            WorkorderLaborEntry entry = openEntry(start);
            when(laborRepository.findById(ENTRY_ID)).thenReturn(Optional.of(entry));

            WorkorderLaborEntryResponse stopped = service.stopLaborSession(ENTRY_ID, null);

            assertThat(stopped.getEndTime()).isEqualTo(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
            assertThat(stopped.getHoursWorked()).isEqualByComparingTo("1.50");
            assertThat(stopped.isActive()).isFalse();
        }

        @Test
        @DisplayName("refuses to stop a session that is already closed")
        void rejectsAlreadyStopped() {
            LocalDateTime start = LocalDateTime.ofInstant(NOW, ZoneOffset.UTC).minusHours(2);
            WorkorderLaborEntry entry = openEntry(start);
            entry.stop(start.plusHours(1));
            when(laborRepository.findById(ENTRY_ID)).thenReturn(Optional.of(entry));

            assertThatThrownBy(() -> service.stopLaborSession(ENTRY_ID, null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Labor session already stopped");
        }

        @Test
        @DisplayName("rejects an unknown entry")
        void rejectsUnknownEntry() {
            when(laborRepository.findById(ENTRY_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.stopLaborSession(ENTRY_ID, null))
                    .isInstanceOf(NoSuchElementException.class)
                    .hasMessageContaining("Labor entry not found");
        }

        @Test
        @DisplayName("registers the idempotency key under the stop operation")
        void registersIdempotencyKey() {
            when(laborRepository.findById(ENTRY_ID))
                    .thenReturn(Optional.of(openEntry(
                            LocalDateTime.ofInstant(NOW, ZoneOffset.UTC).minusMinutes(30))));

            service.stopLaborSession(ENTRY_ID, "stop-key");

            verify(idempotencyService).registerLaborKey(STOP_OPERATION, "stop-key", ENTRY_ID);
        }

        @Test
        @DisplayName("replays the recorded entry when the idempotency key was already used")
        void replaysExistingEntry() {
            WorkorderLaborEntry existing = openEntry(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
            when(idempotencyService.getExistingLaborEntryId(STOP_OPERATION, "stop-key"))
                    .thenReturn(Optional.of(ENTRY_ID));
            when(laborRepository.findById(ENTRY_ID)).thenReturn(Optional.of(existing));

            assertThat(service.stopLaborSession(ENTRY_ID, "stop-key"))
                    .isEqualTo(WorkorderLaborEntryResponse.fromEntity(existing));
            verify(laborRepository, never()).save(any());
        }

        @Test
        @DisplayName("fails when the replayed entry has vanished")
        void failsWhenReplayedEntryMissing() {
            when(idempotencyService.getExistingLaborEntryId(STOP_OPERATION, "stop-key"))
                    .thenReturn(Optional.of(ENTRY_ID));
            when(laborRepository.findById(ENTRY_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.stopLaborSession(ENTRY_ID, "stop-key"))
                    .isInstanceOf(NoSuchElementException.class)
                    .hasMessageContaining("Labor entry not found");
        }
    }

    @Nested
    @DisplayName("adjustLaborHours")
    class AdjustLaborHours {

        @Test
        @DisplayName("overwrites hours worked and records the reason")
        void adjustsHours() {
            WorkorderLaborEntry entry = openEntry(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
            when(laborRepository.findById(ENTRY_ID)).thenReturn(Optional.of(entry));

            WorkorderLaborEntryResponse adjusted =
                    service.adjustLaborHours(ENTRY_ID, new BigDecimal("2.25"), "customer disputed", "jane.smith", null);

            assertThat(adjusted.getHoursWorked()).isEqualByComparingTo("2.25");
            assertThat(adjusted.getAdjustmentReason()).isEqualTo("customer disputed");

            ArgumentCaptor<WorkorderLaborEntry> captor = ArgumentCaptor.forClass(WorkorderLaborEntry.class);
            verify(laborRepository).save(captor.capture());
            assertThat(captor.getValue()).isSameAs(entry);
        }

        @Test
        @DisplayName("rejects negative hours")
        void rejectsNegativeHours() {
            when(laborRepository.findById(ENTRY_ID))
                    .thenReturn(Optional.of(openEntry(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC))));

            assertThatThrownBy(
                            () -> service.adjustLaborHours(ENTRY_ID, new BigDecimal("-1"), "typo", "jane.smith", null))
                    .isInstanceOf(WorkorderRequestValidationException.class)
                    .hasMessage("Hours worked cannot be negative");
        }

        @Test
        @DisplayName("rejects an unknown entry")
        void rejectsUnknownEntry() {
            when(laborRepository.findById(ENTRY_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.adjustLaborHours(ENTRY_ID, BigDecimal.ONE, "reason", "jane.smith", null))
                    .isInstanceOf(LaborEntryNotFoundException.class)
                    .hasMessageContaining("Labor entry not found");
        }

        @Test
        @DisplayName("registers the idempotency key under the adjust operation")
        void registersIdempotencyKey() {
            when(laborRepository.findById(ENTRY_ID))
                    .thenReturn(Optional.of(openEntry(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC))));

            service.adjustLaborHours(ENTRY_ID, BigDecimal.ONE, "reason", "jane.smith", "adjust-key");

            verify(idempotencyService).registerLaborKey(ADJUST_OPERATION, "adjust-key", ENTRY_ID);
        }

        @Test
        @DisplayName("replays the recorded entry when the idempotency key was already used")
        void replaysExistingEntry() {
            WorkorderLaborEntry existing = openEntry(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
            when(idempotencyService.getExistingLaborEntryId(ADJUST_OPERATION, "adjust-key"))
                    .thenReturn(Optional.of(ENTRY_ID));
            when(laborRepository.findById(ENTRY_ID)).thenReturn(Optional.of(existing));

            assertThat(service.adjustLaborHours(ENTRY_ID, BigDecimal.ONE, "reason", "jane.smith", "adjust-key"))
                    .isEqualTo(WorkorderLaborEntryResponse.fromEntity(existing));
            verify(laborRepository, never()).save(any());
        }

        @Test
        @DisplayName("fails when the replayed entry has vanished")
        void failsWhenReplayedEntryMissing() {
            when(idempotencyService.getExistingLaborEntryId(ADJUST_OPERATION, "adjust-key"))
                    .thenReturn(Optional.of(ENTRY_ID));
            when(laborRepository.findById(ENTRY_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() ->
                            service.adjustLaborHours(ENTRY_ID, BigDecimal.ONE, "reason", "jane.smith", "adjust-key"))
                    .isInstanceOf(LaborEntryNotFoundException.class)
                    .hasMessageContaining("Labor entry not found");
        }
    }

    @Test
    @DisplayName("labor history is delegated to the repository, newest first")
    void returnsLaborHistory() {
        WorkorderLaborEntry entry = openEntry(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
        when(laborRepository.findByWorkorder_IdOrderByStartTimeDesc(WORKORDER_ID))
                .thenReturn(List.of(entry));

        assertThat(service.getLaborHistory(WORKORDER_ID))
                .containsExactly(WorkorderLaborEntryResponse.fromEntity(entry));
    }
}
