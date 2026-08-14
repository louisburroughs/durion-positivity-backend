package com.positivity.people.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.domainevents.peoplecontact.PersonDeletedV1;
import com.positivity.domainevents.peoplecontact.PersonUpdatedV1;
import com.positivity.domainevents.peoplecontact.UserPersonLinkRemovedV1;
import com.positivity.domainevents.peoplecontact.UserPersonLinkUpdatedV1;
import com.positivity.people.internal.entity.ExtPersonReplica;
import com.positivity.people.internal.entity.ExtUserLinkReplica;
import com.positivity.people.internal.entity.ProcessedEvent;
import com.positivity.people.internal.repository.ExtPersonReplicaRepository;
import com.positivity.people.internal.repository.ExtUserLinkReplicaRepository;
import com.positivity.people.internal.repository.ProcessedEventRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.QueryTimeoutException;
import tools.jackson.databind.ObjectMapper;

/**
 * Unit tests for {@link PeopleContactEventsListener} (ADR-0044 §6, #875).
 *
 * <p>
 * This listener's dedup contract is deliberately the <em>opposite</em> of the
 * one in pos-order's replica listeners, and that difference is the thing most
 * likely to be "corrected" by someone copying a sibling over it: an event type
 * this module does not consume still gets a {@code processed_events} row.
 * The owner's manifest counts every fact in the window, so skipping the insert
 * would read as replica drift and trigger a pointless replay.
 *
 * <p>
 * Two other decisions are load-bearing:
 *
 * <ul>
 * <li><b>The stale guard skips only strictly-lower versions.</b> The producer's
 * {@code aggregateVersion} is an emission-timestamp LWW hint rather than a JPA
 * version, so an equal version re-applies — harmless, because the payload is a
 * full snapshot, and safer than dropping a legitimate redelivery.</li>
 * <li><b>A transient database error is rethrown</b> for container retry/DLQ,
 * while a malformed payload is swallowed. A poison message must not wedge the
 * partition, but a database blip must not silently drop a fact.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("PeopleContactEventsListener — person and user-link replicas")
class PeopleContactEventsListenerTest {

    private static final UUID PERSON_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID LINK_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
    private static final Instant NOW = Instant.parse("2026-08-11T09:00:00Z");

    @Mock
    private ProcessedEventRepository processedEventRepository;

    @Mock
    private ExtPersonReplicaRepository extPersonReplicaRepository;

    @Mock
    private ExtUserLinkReplicaRepository extUserLinkReplicaRepository;

    private PeopleContactEventsListener listener;

    @BeforeEach
    void setUp() {
        listener = new PeopleContactEventsListener(
                Clock.fixed(NOW, ZoneOffset.UTC),
                new ObjectMapper(),
                processedEventRepository,
                extPersonReplicaRepository,
                extUserLinkReplicaRepository,
                org.mockito.Mockito.mock(ObjectProvider.class));
        when(processedEventRepository.existsById(any())).thenReturn(false);
        when(extPersonReplicaRepository.findById(any())).thenReturn(Optional.empty());
        when(extUserLinkReplicaRepository.findById(any())).thenReturn(Optional.empty());
    }

    private static String personUpdated(String eventId, long version, String contactPointsJson) {
        return """
                {"eventId":"%s","eventType":"%s","aggregateVersion":%d,
                 "payload":{"personId":"%s","firstName":"Ada","lastName":"Lovelace",
                   "preferredName":"Ada","contactPoints":%s,"postalAddress":null,
                   "createdAt":"2026-01-01T00:00:00Z","updatedAt":"2026-08-01T00:00:00Z"}}
                """.formatted(eventId, PersonUpdatedV1.EVENT_TYPE, version, PERSON_ID, contactPointsJson);
    }

    private static String contact(String type, String value, boolean primary) {
        return """
                {"contactType":"%s","value":"%s","primary":%s}""".formatted(type, value, primary);
    }

    private ExtPersonReplica capturePerson() {
        ArgumentCaptor<ExtPersonReplica> captor = ArgumentCaptor.forClass(ExtPersonReplica.class);
        verify(extPersonReplicaRepository).save(captor.capture());
        return captor.getValue();
    }

    @Nested
    @DisplayName("delivery contract")
    class DeliveryContract {

        @Test
        @DisplayName("records a dedup row even for an event type this module does not consume")
        void ignoredTypeStillRecordsProcessedEvent() {
            listener.onPeopleContactEvent("""
                    {"eventId":"evt-1","eventType":"people-contact.something.else","payload":{}}""");

            // Opposite of the pos-order replica listeners on purpose: the owner's manifest counts
            // every fact in the window, so omitting this row would look like drift and force a
            // replay that has nothing to repair.
            ArgumentCaptor<ProcessedEvent> captor = ArgumentCaptor.forClass(ProcessedEvent.class);
            verify(processedEventRepository).save(captor.capture());
            assertThat(captor.getValue().getEventId()).isEqualTo("evt-1");
            assertThat(captor.getValue().getOwner()).isEqualTo("people-contact");
            assertThat(captor.getValue().getProcessedAt()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("skips a message that is not JSON without recording it")
        void unparsableMessageIsSkipped() {
            listener.onPeopleContactEvent("{not json");

            verify(processedEventRepository, never()).save(any());
        }

        @Test
        @DisplayName("skips an envelope with no eventId, since nothing could de-duplicate it")
        void missingEventIdIsSkipped() {
            listener.onPeopleContactEvent(personUpdated("", 1, "[]"));

            verify(processedEventRepository, never()).save(any());
            verify(extPersonReplicaRepository, never()).save(any());
        }

        @Test
        @DisplayName("no-ops on a replayed eventId")
        void replayIsIgnored() {
            when(processedEventRepository.existsById("evt-1")).thenReturn(true);

            listener.onPeopleContactEvent(personUpdated("evt-1", 1, "[]"));

            verify(extPersonReplicaRepository, never()).save(any());
            verify(processedEventRepository, never()).save(any());
        }

        @Test
        @DisplayName("rethrows a transient database error so the container retries instead of losing the fact")
        void transientErrorIsRethrown() {
            when(extPersonReplicaRepository.findById(PERSON_ID)).thenThrow(new QueryTimeoutException("lock wait"));

            assertThatThrownBy(() -> listener.onPeopleContactEvent(personUpdated("evt-1", 1, "[]")))
                    .isInstanceOf(QueryTimeoutException.class);

            verify(processedEventRepository, never()).save(any());
        }

        @Test
        @DisplayName("swallows a malformed payload but still records the event as seen")
        void malformedPayloadIsSwallowed() {
            listener.onPeopleContactEvent("""
                    {"eventId":"evt-1","eventType":"%s","aggregateVersion":1,
                     "payload":{"personId":"not-a-uuid"}}""".formatted(PersonUpdatedV1.EVENT_TYPE));

            // A poison message must not wedge the partition, and the manifest still counted it.
            verify(extPersonReplicaRepository, never()).save(any());
            verify(processedEventRepository).save(any());
        }
    }

    @Nested
    @DisplayName("person replica")
    class PersonReplica {

        @Test
        @DisplayName("splits contact points into primary and secondary email and the first two work phones")
        void projectsContactPoints() {
            listener.onPeopleContactEvent(personUpdated(
                    "evt-1",
                    3,
                    "["
                            + contact("EMAIL", "ada@primary.com", true) + ","
                            + contact("EMAIL", "ada@backup.com", false) + ","
                            + contact("PHONE_WORK", "555-0001", true) + ","
                            + contact("PHONE_WORK", "555-0002", false) + ","
                            + contact("PHONE_WORK", "555-0003", false) + ","
                            + contact("PHONE_MOBILE", "555-9999", true) + "]"));

            ExtPersonReplica saved = capturePerson();
            assertThat(saved.getPersonId()).isEqualTo(PERSON_ID);
            assertThat(saved.getFirstName()).isEqualTo("Ada");
            assertThat(saved.getPreferredName()).isEqualTo("Ada");
            assertThat(saved.getPrimaryEmail()).isEqualTo("ada@primary.com");
            assertThat(saved.getSecondaryEmail()).isEqualTo("ada@backup.com");
            // Work phones are taken in payload order, and only the first two are kept.
            assertThat(saved.getPrimaryPhone()).isEqualTo("555-0001");
            assertThat(saved.getSecondaryPhone()).isEqualTo("555-0002");
            assertThat(saved.getAggregateVersion()).isEqualTo(3);
            assertThat(saved.getUpdatedAt()).isEqualTo(NOW);
            assertThat(saved.getPersonUpdatedAt()).isEqualTo(Instant.parse("2026-08-01T00:00:00Z"));
        }

        @Test
        @DisplayName("leaves the projected fields null when the person has no matching contact points")
        void missingContactPointsBecomeNull() {
            listener.onPeopleContactEvent(
                    personUpdated("evt-1", 1, "[" + contact("PHONE_MOBILE", "555-9999", true) + "]"));

            ExtPersonReplica saved = capturePerson();
            assertThat(saved.getPrimaryEmail()).isNull();
            assertThat(saved.getSecondaryEmail()).isNull();
            assertThat(saved.getPrimaryPhone()).isNull();
            assertThat(saved.getSecondaryPhone()).isNull();
        }

        @Test
        @DisplayName("ignores a snapshot strictly older than the replica")
        void olderSnapshotIsIgnored() {
            ExtPersonReplica existing = ExtPersonReplica.builder()
                    .personId(PERSON_ID)
                    .aggregateVersion(5)
                    .build();
            when(extPersonReplicaRepository.findById(PERSON_ID)).thenReturn(Optional.of(existing));

            listener.onPeopleContactEvent(personUpdated("evt-1", 4, "[]"));

            verify(extPersonReplicaRepository, never()).save(any());
            verify(processedEventRepository).save(any());
        }

        @Test
        @DisplayName("re-applies a snapshot at the same version, since the version is only an LWW hint")
        void equalVersionReapplies() {
            ExtPersonReplica existing = ExtPersonReplica.builder()
                    .personId(PERSON_ID)
                    .aggregateVersion(5)
                    .build();
            when(extPersonReplicaRepository.findById(PERSON_ID)).thenReturn(Optional.of(existing));

            listener.onPeopleContactEvent(personUpdated("evt-1", 5, "[]"));

            // The producer's version is an emission timestamp, not a strict JPA version, so
            // dropping an equal-version redelivery would risk discarding a legitimate update.
            // Re-applying is harmless because the payload is a full snapshot.
            assertThat(capturePerson().getAggregateVersion()).isEqualTo(5);
        }

        @Test
        @DisplayName("removes the replica row on a deletion")
        void deleteRemovesReplica() {
            listener.onPeopleContactEvent("""
                    {"eventId":"evt-2","eventType":"%s","payload":{"personId":"%s"}}""".formatted(PersonDeletedV1.EVENT_TYPE, PERSON_ID));

            verify(extPersonReplicaRepository).deleteById(PERSON_ID);
            verify(extPersonReplicaRepository, never()).save(any());
            verify(processedEventRepository).save(any());
        }
    }

    @Nested
    @DisplayName("user-link replica")
    class UserLinkReplica {

        private static String linkUpdated(long version) {
            return """
                    {"eventId":"evt-3","eventType":"%s","aggregateVersion":%d,
                     "payload":{"linkId":"%s","personId":"%s","username":"ada","status":"ACTIVE",
                       "linkType":"PRIMARY","createdAt":"2026-01-01T00:00:00Z",
                       "updatedAt":"2026-08-01T00:00:00Z"}}
                    """.formatted(UserPersonLinkUpdatedV1.EVENT_TYPE, version, LINK_ID, PERSON_ID);
        }

        @Test
        @DisplayName("replicates the link that ties a login to a person")
        void replicatesLink() {
            listener.onPeopleContactEvent(linkUpdated(2));

            ArgumentCaptor<ExtUserLinkReplica> captor = ArgumentCaptor.forClass(ExtUserLinkReplica.class);
            verify(extUserLinkReplicaRepository).save(captor.capture());
            ExtUserLinkReplica saved = captor.getValue();
            assertThat(saved.getLinkId()).isEqualTo(LINK_ID);
            assertThat(saved.getPersonId()).isEqualTo(PERSON_ID);
            assertThat(saved.getUsername()).isEqualTo("ada");
            assertThat(saved.getStatus()).isEqualTo("ACTIVE");
            assertThat(saved.getAggregateVersion()).isEqualTo(2);
            assertThat(saved.getUpdatedAt()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("ignores a link snapshot strictly older than the replica")
        void olderLinkSnapshotIsIgnored() {
            when(extUserLinkReplicaRepository.findById(LINK_ID))
                    .thenReturn(Optional.of(ExtUserLinkReplica.builder()
                            .linkId(LINK_ID)
                            .aggregateVersion(9)
                            .build()));

            listener.onPeopleContactEvent(linkUpdated(4));

            verify(extUserLinkReplicaRepository, never()).save(any());
        }

        @Test
        @DisplayName("removes the link replica when the link is revoked upstream")
        void linkRemovalDeletesReplica() {
            listener.onPeopleContactEvent("""
                    {"eventId":"evt-4","eventType":"%s",
                     "payload":{"linkId":"%s","personId":"%s","username":"ada"}}""".formatted(UserPersonLinkRemovedV1.EVENT_TYPE, LINK_ID, PERSON_ID));

            verify(extUserLinkReplicaRepository).deleteById(LINK_ID);
            verify(extUserLinkReplicaRepository, never()).save(any());
        }
    }
}
