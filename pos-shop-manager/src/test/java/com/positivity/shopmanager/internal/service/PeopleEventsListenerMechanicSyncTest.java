package com.positivity.shopmanager.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.positivity.shopmanager.internal.entity.ExtPersonReplica;
import com.positivity.shopmanager.internal.entity.ExtStaffingAssignmentReplica;
import com.positivity.shopmanager.internal.repository.ExtPersonReplicaRepository;
import com.positivity.shopmanager.internal.repository.ExtStaffingAssignmentReplicaRepository;
import com.positivity.shopmanager.internal.repository.ProcessedEventRepository;
import com.positivity.shopmanager.service.MechanicSyncService;
import com.positivity.shopmanager.service.dto.HrMechanicEvent;
import com.positivity.shopmanager.service.enums.HrEventType;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.ObjectMapper;

/**
 * Mechanic-feed behavior of {@link PeopleEventsListener}: TECHNICIAN staffing assignments and
 * terminal employee statuses drive {@link MechanicSyncService}. Envelope/dedupe/stale contracts
 * are covered by {@code ReplicaAndManifestListenerContractTest}.
 */
@SuppressWarnings({"java:S100", "java:S1192", "unchecked"})
class PeopleEventsListenerMechanicSyncTest {

    private static final UUID PERSON_ID = UUID.fromString("01960011-0000-7000-8000-000000000005");
    private static final String EVENT_ID = "01990000-0000-7000-8000-000000000001";

    private final Clock clock = Clock.fixed(Instant.parse("2026-08-26T12:00:00Z"), ZoneOffset.UTC);
    private final ProcessedEventRepository processedEventRepository = mock(ProcessedEventRepository.class);
    private final ExtStaffingAssignmentReplicaRepository assignmentRepository =
            mock(ExtStaffingAssignmentReplicaRepository.class);
    private final MechanicSyncService mechanicSyncService = mock(MechanicSyncService.class);
    private final ExtPersonReplicaRepository personReplicaRepository = mock(ExtPersonReplicaRepository.class);

    private PeopleEventsListener listener;

    @BeforeEach
    void setUp() {
        listener = new PeopleEventsListener(
                clock,
                new ObjectMapper(),
                processedEventRepository,
                assignmentRepository,
                mechanicSyncService,
                personReplicaRepository,
                mock(ObjectProvider.class));
        when(processedEventRepository.existsById(any())).thenReturn(false);
        when(assignmentRepository.findById(any())).thenReturn(Optional.empty());
        when(personReplicaRepository.findById(any())).thenReturn(Optional.empty());
        when(assignmentRepository.findByPersonIdAndStatus(any(), any())).thenReturn(List.of());
    }

    private String assignmentEvent(String role, String status) {
        return """
                {"eventId":"%s","eventType":"people.staffing-assignment.updated","aggregateVersion":1756200000000,
                 "payload":{"assignmentId":"01990000-0000-7000-8000-00000000000a",
                            "employeeId":"01990000-0000-7000-8000-00000000000b",
                            "personId":"%s",
                            "locationId":"01990000-0000-7000-8000-00000000000c",
                            "role":"%s","primary":true,"status":"%s",
                            "effectiveFrom":"2026-02-01","effectiveTo":null}}""".formatted(EVENT_ID, PERSON_ID, role, status);
    }

    private String employeeEvent(String status) {
        return """
                {"eventId":"%s","eventType":"people.employee.updated","aggregateVersion":1756200000000,
                 "payload":{"employeeId":"01990000-0000-7000-8000-00000000000b",
                            "personId":"%s","employeeNumber":"EMP-0005","status":"%s",
                            "hireDate":"2024-01-15","terminationDate":null,"statusEffectiveAt":null}}""".formatted(EVENT_ID, PERSON_ID, status);
    }

    // ─── staffing assignment → mechanic upsert ───────────────────────────────

    @Test
    void activeTechnicianAssignment_upsertsMechanicWithReplicaNames() {
        when(personReplicaRepository.findById(PERSON_ID))
                .thenReturn(Optional.of(ExtPersonReplica.builder()
                        .personId(PERSON_ID)
                        .firstName("Kyle")
                        .lastName("Brennan")
                        .aggregateVersion(1)
                        .updatedAt(Instant.now(clock))
                        .build()));

        listener.onPeopleEvent(assignmentEvent("TECHNICIAN", "ACTIVE"));

        ArgumentCaptor<HrMechanicEvent> captor = ArgumentCaptor.forClass(HrMechanicEvent.class);
        verify(mechanicSyncService).processHrEvent(captor.capture());
        HrMechanicEvent event = captor.getValue();
        assertThat(event.getEventType()).isEqualTo(HrEventType.MECHANIC_UPSERTED);
        assertThat(event.getEventId()).isEqualTo(UUID.fromString(EVENT_ID));
        assertThat(event.getPersonId()).isEqualTo(PERSON_ID.toString());
        assertThat(event.getVersion()).isEqualTo(1756200000000L);
        assertThat(event.getPayload().getFirstName()).isEqualTo("Kyle");
        assertThat(event.getPayload().getLastName()).isEqualTo("Brennan");
        assertThat(event.getPayload().getSkills()).isNull();
    }

    @Test
    void activeTechnicianAssignment_withoutPersonReplica_upsertsWithNullPayload() {
        listener.onPeopleEvent(assignmentEvent("TECHNICIAN", "ACTIVE"));

        ArgumentCaptor<HrMechanicEvent> captor = ArgumentCaptor.forClass(HrMechanicEvent.class);
        verify(mechanicSyncService).processHrEvent(captor.capture());
        assertThat(captor.getValue().getEventType()).isEqualTo(HrEventType.MECHANIC_UPSERTED);
        assertThat(captor.getValue().getPayload()).isNull();
    }

    @Test
    void nonTechnicianAssignment_doesNotTouchMechanicSync() {
        listener.onPeopleEvent(assignmentEvent("DISPATCHER", "ACTIVE"));

        verifyNoInteractions(mechanicSyncService);
        verify(assignmentRepository).save(any());
    }

    @Test
    void staleTechnicianAssignment_skipsReplicaAndMechanicSync() {
        when(assignmentRepository.findById(UUID.fromString("01990000-0000-7000-8000-00000000000a")))
                .thenReturn(Optional.of(ExtStaffingAssignmentReplica.builder()
                        .assignmentId(UUID.fromString("01990000-0000-7000-8000-00000000000a"))
                        .employeeId(UUID.fromString("01990000-0000-7000-8000-00000000000b"))
                        .personId(PERSON_ID)
                        .locationId(UUID.fromString("01990000-0000-7000-8000-00000000000c"))
                        .role("TECHNICIAN")
                        .primary(true)
                        .status("ACTIVE")
                        .aggregateVersion(1756200000001L) // strictly newer than the event
                        .updatedAt(Instant.now(clock))
                        .build()));

        listener.onPeopleEvent(assignmentEvent("TECHNICIAN", "ACTIVE"));

        verifyNoInteractions(mechanicSyncService);
        verify(assignmentRepository, org.mockito.Mockito.never()).save(any());
        verify(processedEventRepository).save(any());
    }

    // ─── staffing assignment → mechanic deactivation ─────────────────────────

    @Test
    void endedTechnicianAssignment_lastOne_deactivatesMechanic() {
        listener.onPeopleEvent(assignmentEvent("TECHNICIAN", "ENDED"));

        ArgumentCaptor<HrMechanicEvent> captor = ArgumentCaptor.forClass(HrMechanicEvent.class);
        verify(mechanicSyncService).processHrEvent(captor.capture());
        assertThat(captor.getValue().getEventType()).isEqualTo(HrEventType.MECHANIC_DEACTIVATED);
        assertThat(captor.getValue().getPersonId()).isEqualTo(PERSON_ID.toString());
    }

    @Test
    void endedTechnicianAssignment_withAnotherActiveTechnicianAssignment_doesNotDeactivate() {
        when(assignmentRepository.findByPersonIdAndStatus(PERSON_ID, "ACTIVE"))
                .thenReturn(List.of(ExtStaffingAssignmentReplica.builder()
                        .assignmentId(UUID.fromString("01990000-0000-7000-8000-0000000000ff"))
                        .employeeId(UUID.fromString("01990000-0000-7000-8000-00000000000b"))
                        .personId(PERSON_ID)
                        .locationId(UUID.fromString("01990000-0000-7000-8000-0000000000cc"))
                        .role("TECHNICIAN")
                        .primary(false)
                        .status("ACTIVE")
                        .aggregateVersion(1)
                        .updatedAt(Instant.now(clock))
                        .build()));

        listener.onPeopleEvent(assignmentEvent("TECHNICIAN", "ENDED"));

        verifyNoInteractions(mechanicSyncService);
    }

    // ─── employee status → mechanic deactivation ─────────────────────────────

    @Test
    void terminatedEmployee_deactivatesMechanic() {
        listener.onPeopleEvent(employeeEvent("TERMINATED"));

        ArgumentCaptor<HrMechanicEvent> captor = ArgumentCaptor.forClass(HrMechanicEvent.class);
        verify(mechanicSyncService).processHrEvent(captor.capture());
        assertThat(captor.getValue().getEventType()).isEqualTo(HrEventType.MECHANIC_DEACTIVATED);
        verify(processedEventRepository).save(any());
    }

    @Test
    void activeEmployee_isIgnoredForMechanicSync() {
        listener.onPeopleEvent(employeeEvent("ACTIVE"));

        verifyNoInteractions(mechanicSyncService);
        verify(processedEventRepository).save(any());
    }
}
