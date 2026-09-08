package com.positivity.securityservice.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.positivity.domainevents.people.StaffingAssignmentUpdatedV1;
import com.positivity.securityservice.internal.entity.ExtStaffingAssignmentReplica;
import com.positivity.securityservice.internal.entity.ProcessedEvent;
import com.positivity.securityservice.internal.repository.ExtStaffingAssignmentReplicaRepository;
import com.positivity.securityservice.internal.repository.ProcessedEventRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.ObjectMapper;

/** Unit tests for the staffing-assignment read-model consumer (ADR-0061 §1, #1867). */
class PeopleEventsListenerTest {

    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2026-09-07T12:00:00Z"), ZoneOffset.UTC);
    private static final UUID ASSIGNMENT_ID = UUID.fromString("00000000-0000-7000-8000-0000000000a1");
    private static final UUID EMPLOYEE_ID = UUID.fromString("00000000-0000-7000-8000-0000000000e1");
    private static final UUID PERSON_ID = UUID.fromString("00000000-0000-7000-8000-0000000000f1");
    private static final UUID SHOP_ID = UUID.fromString("00000000-0000-7000-8000-00000000001a");
    private static final UUID REGION_ID = UUID.fromString("00000000-0000-7000-8000-0000000000c1");

    private final ProcessedEventRepository processedEventRepository = mock(ProcessedEventRepository.class);
    private final ExtStaffingAssignmentReplicaRepository replicaRepository =
            mock(ExtStaffingAssignmentReplicaRepository.class);
    private final PersonTokenRevocationService revocationService = mock(PersonTokenRevocationService.class);

    private PeopleEventsListener listener;

    @BeforeEach
    void setUp() {
        listener = new PeopleEventsListener(
                TEST_CLOCK,
                new ObjectMapper(),
                processedEventRepository,
                replicaRepository,
                revocationService,
                mock(ObjectProvider.class));
        when(processedEventRepository.existsById(any())).thenReturn(false);
        when(replicaRepository.findById(any())).thenReturn(Optional.empty());
    }

    private static String assignmentEvent(
            String eventId,
            long aggregateVersion,
            UUID locationId,
            boolean primary,
            String status,
            String effectiveTo) {
        return assignmentEvent(eventId, aggregateVersion, locationId, primary, status, "2026-01-01", effectiveTo);
    }

    private static String assignmentEvent(
            String eventId,
            long aggregateVersion,
            UUID locationId,
            boolean primary,
            String status,
            String effectiveFrom,
            String effectiveTo) {
        return """
                {"eventId":"%s","eventType":"%s","aggregateVersion":%d,
                 "payload":{"assignmentId":"%s","employeeId":"%s","personId":"%s","locationId":"%s",
                            "role":"TECHNICIAN","primary":%s,"status":"%s",
                            "effectiveFrom":"%s","effectiveTo":%s}}
                """.formatted(
                        eventId,
                        StaffingAssignmentUpdatedV1.EVENT_TYPE,
                        aggregateVersion,
                        ASSIGNMENT_ID,
                        EMPLOYEE_ID,
                        PERSON_ID,
                        locationId,
                        primary,
                        status,
                        effectiveFrom,
                        effectiveTo == null ? "null" : "\"" + effectiveTo + "\"");
    }

    private static ExtStaffingAssignmentReplica activeRow(UUID locationId, LocalDate effectiveTo, long version) {
        return ExtStaffingAssignmentReplica.builder()
                .assignmentId(ASSIGNMENT_ID)
                .personId(PERSON_ID)
                .locationId(locationId)
                .primary(true)
                .status("ACTIVE")
                .effectiveFrom(LocalDate.of(2026, 1, 1))
                .effectiveTo(effectiveTo)
                .aggregateVersion(version)
                .updatedAt(Instant.EPOCH)
                .build();
    }

    private ExtStaffingAssignmentReplica savedReplica() {
        ArgumentCaptor<ExtStaffingAssignmentReplica> saved =
                ArgumentCaptor.forClass(ExtStaffingAssignmentReplica.class);
        verify(replicaRepository).save(saved.capture());
        return saved.getValue();
    }

    @Test
    @DisplayName("Create fact inserts the projection row keyed by assignmentId with the fields verbatim")
    void createUpserts() {
        listener.onPeopleEvent(
                assignmentEvent("00000000-0000-7000-8000-000000000e01", 100L, SHOP_ID, true, "ACTIVE", null));

        ExtStaffingAssignmentReplica row = savedReplica();
        assertThat(row.getAssignmentId()).isEqualTo(ASSIGNMENT_ID);
        assertThat(row.getPersonId()).isEqualTo(PERSON_ID);
        assertThat(row.getLocationId()).isEqualTo(SHOP_ID);
        assertThat(row.isPrimary()).isTrue();
        assertThat(row.getStatus()).isEqualTo(ExtStaffingAssignmentReplica.STATUS_ACTIVE);
        assertThat(row.getEffectiveFrom()).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(row.getEffectiveTo()).isNull();
        assertThat(row.getAggregateVersion()).isEqualTo(100L);
        assertThat(row.getUpdatedAt()).isEqualTo(TEST_CLOCK.instant());

        ArgumentCaptor<ProcessedEvent> processed = ArgumentCaptor.forClass(ProcessedEvent.class);
        verify(processedEventRepository).save(processed.capture());
        assertThat(processed.getValue().getEventId()).isEqualTo("00000000-0000-7000-8000-000000000e01");
        assertThat(processed.getValue().getOwner()).isEqualTo("people");
        // Brand-new ACTIVE assignment widens; the next token picks it up (#1874).
        verifyNoInteractions(revocationService);
    }

    @Test
    @DisplayName("Update fact with a newer aggregateVersion overwrites the existing row")
    void updateOverwritesNewerVersion() {
        when(replicaRepository.findById(ASSIGNMENT_ID))
                .thenReturn(Optional.of(ExtStaffingAssignmentReplica.builder()
                        .assignmentId(ASSIGNMENT_ID)
                        .personId(PERSON_ID)
                        .locationId(SHOP_ID)
                        .primary(false)
                        .status("ACTIVE")
                        .effectiveFrom(LocalDate.of(2026, 1, 1))
                        .aggregateVersion(100L)
                        .updatedAt(Instant.EPOCH)
                        .build()));

        listener.onPeopleEvent(
                assignmentEvent("00000000-0000-7000-8000-000000000e02", 200L, SHOP_ID, true, "ACTIVE", "2026-12-31"));

        ExtStaffingAssignmentReplica row = savedReplica();
        assertThat(row.isPrimary()).isTrue();
        assertThat(row.getEffectiveTo()).isEqualTo(LocalDate.of(2026, 12, 31));
        assertThat(row.getAggregateVersion()).isEqualTo(200L);
    }

    @Test
    @DisplayName("End fact marks the row ENDED and keeps it (no delete)")
    void endMarksRowEndedWithoutDeleting() {
        listener.onPeopleEvent(
                assignmentEvent("00000000-0000-7000-8000-000000000e03", 300L, SHOP_ID, true, "ENDED", "2026-09-06"));

        ExtStaffingAssignmentReplica row = savedReplica();
        assertThat(row.getStatus()).isEqualTo(ExtStaffingAssignmentReplica.STATUS_ENDED);
        assertThat(row.getEffectiveTo()).isEqualTo(LocalDate.of(2026, 9, 6));
        verify(replicaRepository, never()).deleteById(any());
        verify(replicaRepository, never()).delete(any());
        // No prior row: nothing was ever issued from this assignment, so nothing to revoke.
        verifyNoInteractions(revocationService);
    }

    @Test
    @DisplayName("Replay of an already-processed eventId is a no-op")
    void replaySameEventIdIsNoOp() {
        when(processedEventRepository.existsById("00000000-0000-7000-8000-000000000e04"))
                .thenReturn(true);

        listener.onPeopleEvent(
                assignmentEvent("00000000-0000-7000-8000-000000000e04", 400L, SHOP_ID, true, "ACTIVE", null));

        verify(replicaRepository, never()).findById(any());
        verify(replicaRepository, never()).save(any());
        verify(processedEventRepository, never()).save(any());
        verifyNoInteractions(revocationService);
    }

    @Test
    @DisplayName("Older aggregateVersion does not overwrite a newer row, but is still marked processed")
    void olderVersionDoesNotOverwrite() {
        when(replicaRepository.findById(ASSIGNMENT_ID))
                .thenReturn(Optional.of(ExtStaffingAssignmentReplica.builder()
                        .assignmentId(ASSIGNMENT_ID)
                        .personId(PERSON_ID)
                        .locationId(SHOP_ID)
                        .status("ENDED")
                        .aggregateVersion(500L)
                        .updatedAt(Instant.EPOCH)
                        .build()));

        listener.onPeopleEvent(
                assignmentEvent("00000000-0000-7000-8000-000000000e05", 499L, SHOP_ID, true, "ACTIVE", null));

        verify(replicaRepository, never()).save(any());
        verify(processedEventRepository).save(any());
    }

    @Test
    @DisplayName("Equal aggregateVersion is applied (last-writer-wins compares with >=)")
    void equalVersionIsApplied() {
        when(replicaRepository.findById(ASSIGNMENT_ID))
                .thenReturn(Optional.of(ExtStaffingAssignmentReplica.builder()
                        .assignmentId(ASSIGNMENT_ID)
                        .personId(PERSON_ID)
                        .locationId(SHOP_ID)
                        .status("ACTIVE")
                        .aggregateVersion(600L)
                        .updatedAt(Instant.EPOCH)
                        .build()));

        listener.onPeopleEvent(
                assignmentEvent("00000000-0000-7000-8000-000000000e06", 600L, SHOP_ID, true, "ENDED", null));

        assertThat(savedReplica().getStatus()).isEqualTo(ExtStaffingAssignmentReplica.STATUS_ENDED);
    }

    @Test
    @DisplayName("A parent node (Region) id is stored verbatim — no descendant expansion")
    void parentNodeStoredVerbatim() {
        listener.onPeopleEvent(
                assignmentEvent("00000000-0000-7000-8000-000000000e07", 700L, REGION_ID, false, "ACTIVE", null));

        ExtStaffingAssignmentReplica row = savedReplica();
        assertThat(row.getLocationId()).isEqualTo(REGION_ID);
        // is_primary is carried as-is and is not interpreted here.
        assertThat(row.isPrimary()).isFalse();
    }

    @Test
    @DisplayName("Other event types on the topic are ignored but recorded in processed_events")
    void otherEventTypesIgnoredButRecorded() {
        listener.onPeopleEvent("""
                {"eventId":"00000000-0000-7000-8000-000000000e08","eventType":"people.employee.updated",
                 "aggregateVersion":1,"payload":{"employeeId":"%s","employeeNumber":"E-1","status":"ACTIVE"}}
                """.formatted(EMPLOYEE_ID));

        verify(replicaRepository, never()).save(any());
        ArgumentCaptor<ProcessedEvent> processed = ArgumentCaptor.forClass(ProcessedEvent.class);
        verify(processedEventRepository).save(processed.capture());
        assertThat(processed.getValue().getEventId()).isEqualTo("00000000-0000-7000-8000-000000000e08");
    }

    @Test
    @DisplayName("Events without an eventId are skipped entirely")
    void missingEventIdSkipped() {
        listener.onPeopleEvent("""
                {"eventType":"%s","aggregateVersion":1,"payload":{}}
                """.formatted(StaffingAssignmentUpdatedV1.EVENT_TYPE));

        verify(replicaRepository, never()).save(any());
        verify(processedEventRepository, never()).save(any());
    }

    @Test
    @DisplayName("A malformed payload is rejected and marked processed so replay does not loop")
    void malformedPayloadRejectedAndRecorded() {
        listener.onPeopleEvent("""
                {"eventId":"00000000-0000-7000-8000-000000000e09","eventType":"%s","aggregateVersion":1,
                 "payload":{"assignmentId":"not-a-uuid"}}
                """.formatted(StaffingAssignmentUpdatedV1.EVENT_TYPE));

        verify(replicaRepository, never()).save(any());
        verify(processedEventRepository).save(any());
    }

    @Test
    @DisplayName("ACTIVE -> ENDED revokes the person's live tokens (and only that person's) after the upsert")
    void endedRevokesLiveTokensOfThatPerson() {
        when(replicaRepository.findById(ASSIGNMENT_ID)).thenReturn(Optional.of(activeRow(SHOP_ID, null, 100L)));
        when(revocationService.revokeLiveTokens(PERSON_ID)).thenReturn(2);

        listener.onPeopleEvent(
                assignmentEvent("00000000-0000-7000-8000-000000000e10", 200L, SHOP_ID, true, "ENDED", "2026-09-06"));

        assertThat(savedReplica().getStatus()).isEqualTo(ExtStaffingAssignmentReplica.STATUS_ENDED);
        verify(revocationService).revokeLiveTokens(PERSON_ID);
        verifyNoMoreInteractions(revocationService);
        verify(processedEventRepository).save(any());
    }

    @Test
    @DisplayName("effectiveTo moved earlier revokes")
    void earlierEffectiveToRevokes() {
        when(replicaRepository.findById(ASSIGNMENT_ID))
                .thenReturn(Optional.of(activeRow(SHOP_ID, LocalDate.of(2026, 12, 31), 100L)));

        listener.onPeopleEvent(
                assignmentEvent("00000000-0000-7000-8000-000000000e11", 200L, SHOP_ID, true, "ACTIVE", "2026-09-30"));

        verify(revocationService).revokeLiveTokens(PERSON_ID);
    }

    @Test
    @DisplayName("locationId change revokes (the old node is no longer covered)")
    void locationChangeRevokes() {
        when(replicaRepository.findById(ASSIGNMENT_ID)).thenReturn(Optional.of(activeRow(SHOP_ID, null, 100L)));

        listener.onPeopleEvent(
                assignmentEvent("00000000-0000-7000-8000-000000000e12", 200L, REGION_ID, true, "ACTIVE", null));

        assertThat(savedReplica().getLocationId()).isEqualTo(REGION_ID);
        verify(revocationService).revokeLiveTokens(PERSON_ID);
    }

    @Test
    @DisplayName("effectiveFrom moved after today revokes")
    void effectiveFromMovedLaterRevokes() {
        when(replicaRepository.findById(ASSIGNMENT_ID)).thenReturn(Optional.of(activeRow(SHOP_ID, null, 100L)));

        listener.onPeopleEvent(assignmentEvent(
                "00000000-0000-7000-8000-000000000e13", 200L, SHOP_ID, true, "ACTIVE", "2026-09-08", null));

        verify(revocationService).revokeLiveTokens(PERSON_ID);
    }

    @Test
    @DisplayName("Later or removed effectiveTo widens: no revocation")
    void laterEffectiveToDoesNotRevoke() {
        when(replicaRepository.findById(ASSIGNMENT_ID))
                .thenReturn(Optional.of(activeRow(SHOP_ID, LocalDate.of(2026, 9, 30), 100L)));

        listener.onPeopleEvent(
                assignmentEvent("00000000-0000-7000-8000-000000000e14", 200L, SHOP_ID, true, "ACTIVE", "2026-12-31"));
        listener.onPeopleEvent(
                assignmentEvent("00000000-0000-7000-8000-000000000e15", 300L, SHOP_ID, true, "ACTIVE", null));

        verifyNoInteractions(revocationService);
    }

    @Test
    @DisplayName("A stale (older aggregateVersion) ENDED fact neither overwrites nor revokes")
    void staleEndedFactDoesNotRevoke() {
        when(replicaRepository.findById(ASSIGNMENT_ID)).thenReturn(Optional.of(activeRow(SHOP_ID, null, 500L)));

        listener.onPeopleEvent(
                assignmentEvent("00000000-0000-7000-8000-000000000e16", 499L, SHOP_ID, true, "ENDED", "2026-09-06"));

        verify(replicaRepository, never()).save(any());
        verifyNoInteractions(revocationService);
    }
}
