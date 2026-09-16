package com.positivity.shopmanager.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.positivity.shopmanager.internal.entity.ExtPersonCredentialReplica;
import com.positivity.shopmanager.internal.repository.ExtPersonCredentialReplicaRepository;
import com.positivity.shopmanager.internal.repository.ExtPersonReplicaRepository;
import com.positivity.shopmanager.internal.repository.ExtStaffingAssignmentReplicaRepository;
import com.positivity.shopmanager.internal.repository.ProcessedEventRepository;
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

/**
 * CAP-328: {@code people.person-credential.updated} mirrors into {@code ext_person_credential} —
 * a straight upsert by credential id under the stale guard, status stored as received.
 */
class PeopleEventsListenerCredentialReplicaTest {

    private static final String EVENT_ID = "01990000-0000-7000-8000-000000000101";
    private static final UUID CREDENTIAL_ID = UUID.fromString("01990000-0000-7000-8000-000000000031");
    private static final UUID PERSON_ID = UUID.fromString("01960011-0000-7000-8000-000000000005");

    private final Clock clock = Clock.fixed(Instant.parse("2026-09-16T12:00:00Z"), ZoneOffset.UTC);
    private final ProcessedEventRepository processedEventRepository = mock(ProcessedEventRepository.class);
    private final ExtStaffingAssignmentReplicaRepository assignmentRepository =
            mock(ExtStaffingAssignmentReplicaRepository.class);
    private final MechanicSyncService mechanicSyncService = mock(MechanicSyncService.class);
    private final ExtPersonReplicaRepository personReplicaRepository = mock(ExtPersonReplicaRepository.class);
    private final ExtPersonCredentialReplicaRepository credentialRepository =
            mock(ExtPersonCredentialReplicaRepository.class);

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
                credentialRepository,
                mock(ObjectProvider.class));
        when(processedEventRepository.existsById(any())).thenReturn(false);
        when(credentialRepository.findById(any())).thenReturn(Optional.empty());
    }

    private static String credentialEvent(long aggregateVersion, String status, String expiresOn) {
        return """
                {"eventId":"%s","eventType":"people.person-credential.updated","aggregateVersion":%d,
                 "payload":{"credentialId":"%s","personId":"%s",
                            "skillId":"01990000-0000-7000-8000-000000000041",
                            "skillCode":"BRAKES-MEDIUM_HEAVY","competenceCode":"BRAKES",
                            "minGvwrClass":4,"maxGvwrClass":8,
                            "issuer":"ASE","sourceCode":"ASE","sourceCredentialCode":"T4-BRAKES",
                            "issuedOn":"2021-09-16","expiresOn":%s,"proficiency":4,
                            "status":"%s","evidenceRef":null,"supersededBy":null}}""".formatted(EVENT_ID, aggregateVersion, CREDENTIAL_ID, PERSON_ID, expiresOn, status);
    }

    @Test
    @DisplayName("a credential fact is mirrored field for field, status as received, versioned by the envelope")
    void credentialEvent_upsertsReplicaRow() {
        listener.onPeopleEvent(credentialEvent(7, "ACTIVE", "\"2026-09-15\""));

        ArgumentCaptor<ExtPersonCredentialReplica> captor = ArgumentCaptor.forClass(ExtPersonCredentialReplica.class);
        verify(credentialRepository).save(captor.capture());
        ExtPersonCredentialReplica row = captor.getValue();
        assertThat(row.getCredentialId()).isEqualTo(CREDENTIAL_ID);
        assertThat(row.getPersonId()).isEqualTo(PERSON_ID);
        assertThat(row.getSkillCode()).isEqualTo("BRAKES-MEDIUM_HEAVY");
        assertThat(row.getCompetenceCode()).isEqualTo("BRAKES");
        assertThat(row.getMinGvwrClass()).isEqualTo(4);
        assertThat(row.getMaxGvwrClass()).isEqualTo(8);
        assertThat(row.getIssuer()).isEqualTo("ASE");
        assertThat(row.getSourceCredentialCode()).isEqualTo("T4-BRAKES");
        assertThat(row.getIssuedOn()).isEqualTo(LocalDate.parse("2021-09-16"));
        assertThat(row.getExpiresOn()).isEqualTo(LocalDate.parse("2026-09-15"));
        assertThat(row.getProficiency()).isEqualTo(4);
        // Stored as received; the roster judges expiry on read, and on this clock it has expired.
        assertThat(row.getStatus()).isEqualTo("ACTIVE");
        assertThat(row.statusOn(LocalDate.now(clock)).isHeld()).isFalse();
        assertThat(row.getAggregateVersion()).isEqualTo(7);
        assertThat(row.getUpdatedAt()).isEqualTo(Instant.now(clock));
        verifyNoInteractions(mechanicSyncService, assignmentRepository);
        verify(processedEventRepository).save(any());
    }

    @Test
    @DisplayName("a null expiry is mirrored as null — never expires, not already expired")
    void credentialEvent_nullExpiryStaysNull() {
        listener.onPeopleEvent(credentialEvent(1, "ACTIVE", "null"));

        ArgumentCaptor<ExtPersonCredentialReplica> captor = ArgumentCaptor.forClass(ExtPersonCredentialReplica.class);
        verify(credentialRepository).save(captor.capture());
        assertThat(captor.getValue().getExpiresOn()).isNull();
        assertThat(captor.getValue().statusOn(LocalDate.parse("2099-01-01")).isHeld())
                .isTrue();
    }

    @Test
    @DisplayName("a stale snapshot never moves the replica backwards, but is still marked processed")
    void staleCredentialEvent_isSkipped() {
        when(credentialRepository.findById(CREDENTIAL_ID))
                .thenReturn(Optional.of(ExtPersonCredentialReplica.builder()
                        .credentialId(CREDENTIAL_ID)
                        .personId(PERSON_ID)
                        .status("REVOKED")
                        .aggregateVersion(9)
                        .build()));

        listener.onPeopleEvent(credentialEvent(8, "ACTIVE", "null"));

        verify(credentialRepository, never()).save(any());
        verify(processedEventRepository).save(any());
    }

    @Test
    @DisplayName("an equal version is a replay of the same fact and is applied idempotently")
    void equalVersion_isApplied() {
        when(credentialRepository.findById(CREDENTIAL_ID))
                .thenReturn(Optional.of(ExtPersonCredentialReplica.builder()
                        .credentialId(CREDENTIAL_ID)
                        .personId(PERSON_ID)
                        .status("ACTIVE")
                        .aggregateVersion(8)
                        .build()));

        listener.onPeopleEvent(credentialEvent(8, "SUPERSEDED", "null"));

        ArgumentCaptor<ExtPersonCredentialReplica> captor = ArgumentCaptor.forClass(ExtPersonCredentialReplica.class);
        verify(credentialRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("SUPERSEDED");
    }
}
