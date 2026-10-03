package com.positivity.platformsender.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.domainevents.peoplecontact.PersonUpdatedV1.ContactPointV1;
import com.positivity.platformsender.internal.entity.ExtPeopleContactPerson;
import com.positivity.platformsender.internal.entity.ProcessedEvent;
import com.positivity.platformsender.internal.repository.ExtPeopleContactPersonRepository;
import com.positivity.platformsender.internal.repository.ProcessedEventRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

/**
 * {@link PeopleContactEventsListener}: the email and mobile-phone replica. Idempotent, guarded
 * against stale versions, records ignored types for the manifest, rethrows retryable failures with
 * no mark written.
 */
@DisplayName("PeopleContactEventsListener — address replica")
class PeopleContactEventsListenerTest {

    private static final Instant NOW = Instant.parse("2026-10-03T12:00:00Z");
    private static final UUID PERSON_ID = UUID.fromString("01990000-0000-7000-8000-0000000000b1");
    private static final String EVENT_ID = "01990000-0000-7000-8000-0000000000e1";

    private final ProcessedEventRepository processed = mock(ProcessedEventRepository.class);
    private final ExtPeopleContactPersonRepository persons = mock(ExtPeopleContactPersonRepository.class);
    private PeopleContactEventsListener listener;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        listener = new PeopleContactEventsListener(
                Clock.fixed(NOW, ZoneOffset.UTC),
                new ObjectMapper(),
                processed,
                persons,
                mock(ObjectProvider.class),
                mock(PlatformTransactionManager.class));
        when(persons.findById(PERSON_ID)).thenReturn(Optional.empty());
    }

    private static String personUpdated(long version) {
        return """
                {"eventId":"%s","eventType":"people-contact.person.updated","aggregateVersion":%d,
                 "payload":{"personId":"%s","firstName":"Ada","lastName":"Lovelace",
                  "contactPoints":[{"contactType":"EMAIL","value":"old@example.com","primary":false},
                                   {"contactType":"EMAIL","value":"Ada@Example.com","primary":true},
                                   {"contactType":"PHONE_WORK","value":"5550100199","primary":true},
                                   {"contactType":"PHONE_MOBILE","value":"(555) 010-0100","primary":false}]}}
                """.formatted(EVENT_ID, version, PERSON_ID);
    }

    private ExtPeopleContactPerson saved() {
        ArgumentCaptor<ExtPeopleContactPerson> saved = ArgumentCaptor.captor();
        verify(persons).save(saved.capture());
        return saved.getValue();
    }

    @Test
    @DisplayName("keeps the primary email and the mobile phone as the owner stored them, and marks the event")
    void appliesUpdate() {
        listener.onPeopleContactEvent(personUpdated(1000));

        ExtPeopleContactPerson row = saved();
        assertThat(row.getEmail()).isEqualTo("Ada@Example.com");
        assertThat(row.getMobilePhone()).as("work numbers are not SMS targets").isEqualTo("(555) 010-0100");
        assertThat(row.getAggregateVersion()).isEqualTo(1000);
        ArgumentCaptor<ProcessedEvent> mark = ArgumentCaptor.captor();
        verify(processed).save(mark.capture());
        assertThat(mark.getValue().getOwner()).isEqualTo(PeopleContactEventsListener.OWNER);
    }

    @Test
    @DisplayName("a stale update is skipped but still marked")
    void staleSkipped() {
        when(persons.findById(PERSON_ID))
                .thenReturn(Optional.of(ExtPeopleContactPerson.builder()
                        .personId(PERSON_ID)
                        .aggregateVersion(2000)
                        .build()));

        listener.onPeopleContactEvent(personUpdated(1000));

        verify(persons, never()).save(any());
        verify(processed).save(any());
    }

    @Test
    @DisplayName("a delete tombstones the person: no addresses, the version kept")
    void deleteTombstones() {
        listener.onPeopleContactEvent("""
                {"eventId":"%s","eventType":"people-contact.person.deleted","aggregateVersion":3000,
                 "payload":{"personId":"%s"}}
                """.formatted(EVENT_ID, PERSON_ID));

        ExtPeopleContactPerson row = saved();
        assertThat(row.getEmail()).isNull();
        assertThat(row.getMobilePhone()).isNull();
        assertThat(row.getAggregateVersion()).isEqualTo(3000);
    }

    @Test
    @DisplayName("an already-processed event is skipped entirely")
    void duplicateSkipped() {
        when(processed.existsById(EVENT_ID)).thenReturn(true);

        listener.onPeopleContactEvent(personUpdated(1000));

        verify(persons, never()).save(any());
        verify(processed, never()).save(any());
    }

    @Test
    @DisplayName("an ignored event type is still marked, so the owner's manifest matches")
    void ignoredTypeMarked() {
        listener.onPeopleContactEvent("""
                {"eventId":"%s","eventType":"people-contact.user-person-link.updated","payload":{}}
                """.formatted(EVENT_ID));

        verify(persons, never()).save(any());
        verify(processed).save(any());
    }

    @Test
    @DisplayName("a malformed payload is marked and dropped, not retried")
    void malformedMarked() {
        listener.onPeopleContactEvent("""
                {"eventId":"%s","eventType":"people-contact.person.updated","payload":{"personId":"not-a-uuid"}}
                """.formatted(EVENT_ID));

        verify(persons, never()).save(any());
        verify(processed).save(any());
    }

    @Test
    @DisplayName("a lost connection is rethrown for container retry, with no mark written")
    void lostConnectionRethrown() {
        when(persons.save(any())).thenThrow(new DataAccessResourceFailureException("connection reset"));

        assertThatExceptionOfType(DataAccessResourceFailureException.class)
                .isThrownBy(() -> listener.onPeopleContactEvent(personUpdated(1000)));
        verify(processed, never()).save(any());
    }

    @Test
    @DisplayName("events without an id, or that are not JSON, are dropped")
    void unidentifiedDropped() {
        listener.onPeopleContactEvent("not json");
        listener.onPeopleContactEvent("{\"eventType\":\"people-contact.person.updated\"}");

        verify(processed, never()).save(any());
    }

    @Test
    @DisplayName("the preferred contact point is the primary, else the first, else none")
    void preferredContactPoint() {
        List<ContactPointV1> points = List.of(
                new ContactPointV1("PHONE_MOBILE", "first", false),
                new ContactPointV1("PHONE_MOBILE", "primary", true),
                new ContactPointV1("EMAIL", " ", true));

        assertThat(PeopleContactEventsListener.preferred(points, "PHONE_MOBILE"))
                .isEqualTo("primary");
        assertThat(PeopleContactEventsListener.preferred(points.subList(0, 1), "PHONE_MOBILE"))
                .isEqualTo("first");
        assertThat(PeopleContactEventsListener.preferred(points, "EMAIL")).isNull();
    }
}
