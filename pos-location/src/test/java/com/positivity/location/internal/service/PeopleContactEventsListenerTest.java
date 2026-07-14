package com.positivity.location.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.location.internal.entity.ExtPersonReplica;
import com.positivity.location.internal.repository.ExtPersonReplicaRepository;
import com.positivity.location.internal.repository.ProcessedEventRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

class PeopleContactEventsListenerTest {

    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2026-07-14T12:00:00Z"), ZoneOffset.UTC);
    private static final UUID PERSON_ID = UUID.fromString("01960011-0000-7000-8000-000000000001");

    private final ProcessedEventRepository processedEventRepository = mock(ProcessedEventRepository.class);
    private final ExtPersonReplicaRepository extPersonReplicaRepository = mock(ExtPersonReplicaRepository.class);
    private PeopleContactEventsListener listener;

    @BeforeEach
    void setUp() {
        listener = new PeopleContactEventsListener(
                TEST_CLOCK, new ObjectMapper(), processedEventRepository, extPersonReplicaRepository);
        when(processedEventRepository.existsById(any())).thenReturn(false);
    }

    private String personUpdated(String eventId, long aggregateVersion) {
        return """
                {"eventId":"%s","eventType":"people-contact.person.updated","aggregateVersion":%d,
                 "payload":{"personId":"%s","firstName":"Jane","lastName":"Doe","preferredName":null,
                            "contactPoints":[
                              {"contactType":"EMAIL","value":"jane.doe@example.com","primary":true},
                              {"contactType":"PHONE_MOBILE","value":"+1-217-555-0100","primary":false}]}}
                """.formatted(eventId, aggregateVersion, PERSON_ID);
    }

    @Test
    @DisplayName("person.updated upserts names, primary email, and the contact snapshot")
    void personUpdatedUpsertsReplica() {
        when(extPersonReplicaRepository.findById(PERSON_ID)).thenReturn(Optional.empty());

        listener.onPeopleContactEvent(personUpdated("01980000-0000-7000-8000-000000000e01", 10));

        ArgumentCaptor<ExtPersonReplica> saved = ArgumentCaptor.forClass(ExtPersonReplica.class);
        verify(extPersonReplicaRepository).save(saved.capture());
        assertThat(saved.getValue().getPersonId()).isEqualTo(PERSON_ID);
        assertThat(saved.getValue().getFirstName()).isEqualTo("Jane");
        assertThat(saved.getValue().getLastName()).isEqualTo("Doe");
        assertThat(saved.getValue().getPrimaryEmail()).isEqualTo("jane.doe@example.com");
        assertThat(saved.getValue().getContactPoints()).contains("PHONE_MOBILE");
        assertThat(saved.getValue().getAggregateVersion()).isEqualTo(10);
        verify(processedEventRepository).save(any());
    }

    @Test
    @DisplayName("Stale person.updated (lower aggregateVersion) is skipped but still marked processed")
    void staleUpdateSkipped() {
        when(extPersonReplicaRepository.findById(PERSON_ID))
                .thenReturn(Optional.of(ExtPersonReplica.builder()
                        .personId(PERSON_ID)
                        .aggregateVersion(99)
                        .updatedAt(Instant.parse("2026-07-14T11:00:00Z"))
                        .build()));

        listener.onPeopleContactEvent(personUpdated("01980000-0000-7000-8000-000000000e02", 10));

        verify(extPersonReplicaRepository, never()).save(any());
        verify(processedEventRepository).save(any());
    }

    @Test
    @DisplayName("person.deleted removes the replica row")
    void personDeletedRemovesRow() {
        listener.onPeopleContactEvent("""
                {"eventId":"01980000-0000-7000-8000-000000000e03",
                 "eventType":"people-contact.person.deleted","aggregateVersion":11,
                 "payload":{"personId":"%s"}}
                """.formatted(PERSON_ID));

        verify(extPersonReplicaRepository).deleteById(PERSON_ID);
        verify(processedEventRepository).save(any());
    }

    @Test
    @DisplayName("Duplicate events are skipped via processed_events")
    void duplicateEventsSkipped() {
        when(processedEventRepository.existsById("01980000-0000-7000-8000-000000000e04"))
                .thenReturn(true);

        listener.onPeopleContactEvent(personUpdated("01980000-0000-7000-8000-000000000e04", 12));

        verify(extPersonReplicaRepository, never()).save(any());
        verify(processedEventRepository, never()).save(any());
    }

    @Test
    @DisplayName("Ignored event types still record the eventId for manifest reconciliation")
    void ignoredTypesStillRecorded() {
        listener.onPeopleContactEvent("""
                {"eventId":"01980000-0000-7000-8000-000000000e05",
                 "eventType":"people-contact.user-person-link.updated","aggregateVersion":5,
                 "payload":{"linkId":"01980000-0000-7000-8000-00000000aa01"}}
                """);

        verify(extPersonReplicaRepository, never()).save(any());
        verify(processedEventRepository).save(any());
    }
}
