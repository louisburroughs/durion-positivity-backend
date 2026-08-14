package com.positivity.customer.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.customer.internal.entity.ExtOrganizationPostalAddress;
import com.positivity.customer.internal.entity.ExtPersonReplica;
import com.positivity.customer.internal.entity.ProcessedEvent;
import com.positivity.customer.internal.repository.ExtOrganizationPostalAddressRepository;
import com.positivity.customer.internal.repository.ExtPersonReplicaRepository;
import com.positivity.customer.internal.repository.ProcessedEventRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.QueryTimeoutException;
import tools.jackson.databind.ObjectMapper;

/**
 * Unit tests for {@link PeopleContactEventsListener} (ADR-0044 §6, #877).
 *
 * <p>
 * This listener maintains a read replica of people-contact data. Four rules make
 * that replica trustworthy, and each is easy to break without noticing:
 *
 * <ul>
 * <li><b>Idempotency.</b> An event already in {@code processed_events} is
 * skipped entirely — Kafka redelivery must not re-apply a write.</li>
 * <li><b>Stale-version guard.</b> A payload whose {@code aggregateVersion} is
 * strictly below the stored one is dropped, so a replayed older update cannot
 * resurrect stale data. Equal versions are re-applied, since the version is an
 * emission-timestamp last-writer-wins hint rather than a strict sequence.</li>
 * <li><b>Ignored types still record.</b> An event type this module does not care
 * about is still written to {@code processed_events}: the owner's manifest counts
 * every fact in the window, so omitting the row would read as replica drift and
 * trigger a pointless replay.</li>
 * <li><b>Transient errors escape.</b> A transient data-access failure is
 * rethrown so Kafka retries it, while a malformed payload is swallowed — the
 * former is worth retrying and the latter never will be.</li>
 * </ul>
 */
@DisplayName("PeopleContactEventsListener — replica maintenance and idempotency")
class PeopleContactEventsListenerTest {

    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2026-08-11T12:00:00Z"), ZoneOffset.UTC);
    private static final Instant NOW = Instant.parse("2026-08-11T12:00:00Z");
    private static final UUID PERSON_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID ORG_ID = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    private static final String EVENT_ID = "01960000-0000-7000-8000-000000000001";

    private final ProcessedEventRepository processedEvents = mock(ProcessedEventRepository.class);
    private final ExtPersonReplicaRepository personReplica = mock(ExtPersonReplicaRepository.class);
    private final ExtOrganizationPostalAddressRepository addressReplica =
            mock(ExtOrganizationPostalAddressRepository.class);
    private final CustomerFactPublisher factPublisher = mock(CustomerFactPublisher.class);

    private PeopleContactEventsListener listener;

    @BeforeEach
    void setUp() {
        listener = new PeopleContactEventsListener(
                TEST_CLOCK,
                new ObjectMapper(),
                processedEvents,
                personReplica,
                addressReplica,
                factPublisher,
                mock(ObjectProvider.class));
    }

    private static String personUpdated(long aggregateVersion) {
        return """
                {"eventId":"%s","eventType":"people-contact.person.updated","aggregateVersion":%d,
                 "payload":{"personId":"%s","firstName":"Ada","lastName":"Lovelace","preferredName":"Ada",
                  "contactPoints":[{"contactType":"EMAIL","value":"ada@example.com","primary":true},
                                   {"contactType":"EMAIL","value":"old@example.com","primary":false}],
                  "postalAddress":{"line1":"1 Main St","line2":null,"city":"Springfield","region":"IL",
                                   "postalCode":"62701","countryCode":"US"},
                  "createdAt":"2026-01-01T00:00:00Z","updatedAt":"2026-08-01T00:00:00Z"}}
                """.formatted(EVENT_ID, aggregateVersion, PERSON_ID);
    }

    private static String addressUpdated(long aggregateVersion) {
        return """
                {"eventId":"%s","eventType":"people-contact.organization-address.updated","aggregateVersion":%d,
                 "payload":{"organizationId":"%s",
                  "postalAddress":{"line1":"2 Elm St","line2":"Suite 3","city":"Shelbyville","region":"IL",
                                   "postalCode":"62565","countryCode":"US"},
                  "updatedAt":"2026-08-01T00:00:00Z"}}
                """.formatted(EVENT_ID, aggregateVersion, ORG_ID);
    }

    private static String addressRemoved(long aggregateVersion) {
        return """
                {"eventId":"%s","eventType":"people-contact.organization-address.removed","aggregateVersion":%d,
                 "payload":{"organizationId":"%s"}}
                """.formatted(EVENT_ID, aggregateVersion, ORG_ID);
    }

    private ExtPersonReplica storedPerson(long aggregateVersion) {
        return ExtPersonReplica.builder()
                .personId(PERSON_ID)
                .aggregateVersion(aggregateVersion)
                .build();
    }

    private ExtOrganizationPostalAddress storedAddress(long aggregateVersion) {
        return ExtOrganizationPostalAddress.builder()
                .organizationId(ORG_ID)
                .aggregateVersion(aggregateVersion)
                .build();
    }

    @Nested
    @DisplayName("person updates")
    class PersonUpdates {

        @Test
        @DisplayName("writes the replica row, flattening the primary email out of the contact points")
        void personUpdated_writesReplica() {
            when(personReplica.findById(PERSON_ID)).thenReturn(Optional.empty());

            listener.onPeopleContactEvent(personUpdated(5));

            ArgumentCaptor<ExtPersonReplica> saved = ArgumentCaptor.captor();
            verify(personReplica).save(saved.capture());
            assertThat(saved.getValue().getFirstName()).isEqualTo("Ada");
            // Only the contact point flagged primary is promoted.
            assertThat(saved.getValue().getPrimaryEmail()).isEqualTo("ada@example.com");
            assertThat(saved.getValue().getAddressCity()).isEqualTo("Springfield");
            assertThat(saved.getValue().getAggregateVersion()).isEqualTo(5);
            assertThat(saved.getValue().getUpdatedAt()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("re-emits the person fact so downstream display names pick up the change")
        void personUpdated_republishesFact() {
            when(personReplica.findById(PERSON_ID)).thenReturn(Optional.empty());

            listener.onPeopleContactEvent(personUpdated(5));

            verify(factPublisher).personReplicaChanged(PERSON_ID);
        }

        @Test
        @DisplayName("drops an update whose version is strictly below the stored one")
        void personUpdated_whenStale_isDropped() {
            when(personReplica.findById(PERSON_ID)).thenReturn(Optional.of(storedPerson(9)));

            listener.onPeopleContactEvent(personUpdated(5));

            // A replayed older update must not resurrect stale data.
            verify(personReplica, never()).save(any());
            verify(factPublisher, never()).personReplicaChanged(any());
        }

        @Test
        @DisplayName("re-applies an update at the same version — the version is an LWW hint, not a sequence")
        void personUpdated_whenSameVersion_isApplied() {
            when(personReplica.findById(PERSON_ID)).thenReturn(Optional.of(storedPerson(5)));

            listener.onPeopleContactEvent(personUpdated(5));

            verify(personReplica).save(any());
        }

        @Test
        @DisplayName("records a stale-skipped event as processed so it is not reconsidered")
        void personUpdated_whenStale_stillRecordsProcessed() {
            when(personReplica.findById(PERSON_ID)).thenReturn(Optional.of(storedPerson(9)));

            listener.onPeopleContactEvent(personUpdated(5));

            verify(processedEvents).save(any(ProcessedEvent.class));
        }

        @Test
        @DisplayName("deletes the replica row on a person-deleted fact")
        void personDeleted_deletesReplica() {
            String message = """
                    {"eventId":"%s","eventType":"people-contact.person.deleted","aggregateVersion":3,
                     "payload":{"personId":"%s"}}
                    """.formatted(EVENT_ID, PERSON_ID);

            listener.onPeopleContactEvent(message);

            verify(personReplica).deleteById(PERSON_ID);
        }
    }

    @Nested
    @DisplayName("organization addresses")
    class OrganizationAddresses {

        @Test
        @DisplayName("writes the address replica row")
        void addressUpdated_writesReplica() {
            when(addressReplica.findById(ORG_ID)).thenReturn(Optional.empty());

            listener.onPeopleContactEvent(addressUpdated(4));

            ArgumentCaptor<ExtOrganizationPostalAddress> saved = ArgumentCaptor.captor();
            verify(addressReplica).save(saved.capture());
            assertThat(saved.getValue().getLine1()).isEqualTo("2 Elm St");
            assertThat(saved.getValue().getCity()).isEqualTo("Shelbyville");
            assertThat(saved.getValue().getAggregateVersion()).isEqualTo(4);
        }

        @Test
        @DisplayName("drops an address update whose version is strictly below the stored one")
        void addressUpdated_whenStale_isDropped() {
            when(addressReplica.findById(ORG_ID)).thenReturn(Optional.of(storedAddress(9)));

            listener.onPeopleContactEvent(addressUpdated(4));

            verify(addressReplica, never()).save(any());
        }

        @Test
        @DisplayName("removal writes a versioned tombstone rather than deleting the row")
        void addressRemoved_writesTombstone() {
            when(addressReplica.findById(ORG_ID)).thenReturn(Optional.of(storedAddress(4)));

            listener.onPeopleContactEvent(addressRemoved(7));

            ArgumentCaptor<ExtOrganizationPostalAddress> saved = ArgumentCaptor.captor();
            verify(addressReplica).save(saved.capture());
            // A hard delete would discard the version watermark, letting a replayed
            // older update resurrect a removed address.
            assertThat(saved.getValue().getAggregateVersion()).isEqualTo(7);
            assertThat(saved.getValue().getLine1()).isNull();
            assertThat(saved.getValue().getCity()).isNull();
            verify(addressReplica, never()).deleteById(any());
        }

        @Test
        @DisplayName("drops a stale removal so it cannot wipe a newer address")
        void addressRemoved_whenStale_isDropped() {
            when(addressReplica.findById(ORG_ID)).thenReturn(Optional.of(storedAddress(9)));

            listener.onPeopleContactEvent(addressRemoved(4));

            verify(addressReplica, never()).save(any());
        }
    }

    @Nested
    @DisplayName("envelope handling")
    class EnvelopeHandling {

        @Test
        @DisplayName("skips an event already recorded in processed_events without re-applying it")
        void duplicateEvent_isSkipped() {
            when(processedEvents.existsById(EVENT_ID)).thenReturn(true);

            listener.onPeopleContactEvent(personUpdated(5));

            verify(personReplica, never()).save(any());
            verify(processedEvents, never()).save(any());
        }

        @Test
        @DisplayName("records the event as processed on the happy path, owned by people-contact")
        void processedEvent_isRecordedWithOwner() {
            when(personReplica.findById(PERSON_ID)).thenReturn(Optional.empty());

            listener.onPeopleContactEvent(personUpdated(5));

            ArgumentCaptor<ProcessedEvent> saved = ArgumentCaptor.captor();
            verify(processedEvents).save(saved.capture());
            assertThat(saved.getValue().getEventId()).isEqualTo(EVENT_ID);
            assertThat(saved.getValue().getOwner()).isEqualTo(PeopleContactEventsListener.OWNER);
            assertThat(saved.getValue().getProcessedAt()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("records an ignored event type so the owner's manifest does not read it as drift")
        void unknownEventType_isStillRecorded() {
            String message = """
                    {"eventId":"%s","eventType":"people-contact.something.else","aggregateVersion":1,"payload":{}}
                    """.formatted(EVENT_ID);

            listener.onPeopleContactEvent(message);

            verify(processedEvents).save(any(ProcessedEvent.class));
            verify(personReplica, never()).save(any());
        }

        @Test
        @DisplayName("ignores an unparsable message without recording it")
        void unparsableMessage_isIgnored() {
            listener.onPeopleContactEvent("this is not json");

            verify(processedEvents, never()).save(any());
            verify(personReplica, never()).save(any());
        }

        @Test
        @DisplayName("ignores an envelope with no eventId, since idempotency cannot be tracked without one")
        void missingEventId_isIgnored() {
            listener.onPeopleContactEvent("{\"eventType\":\"people-contact.person.updated\",\"payload\":{}}");

            verify(processedEvents, never()).save(any());
            verify(personReplica, never()).save(any());
        }

        @Test
        @DisplayName("swallows a malformed payload but still records the event")
        void malformedPayload_isRecordedNotRetried() {
            String message = """
                    {"eventId":"%s","eventType":"people-contact.person.updated","aggregateVersion":1,
                     "payload":{"personId":"not-a-uuid"}}
                    """.formatted(EVENT_ID);

            listener.onPeopleContactEvent(message);

            // Retrying a malformed payload would never succeed, so it is recorded
            // and dropped rather than sent round the retry loop.
            verify(processedEvents).save(any(ProcessedEvent.class));
            verify(personReplica, never()).save(any());
        }

        @Test
        @DisplayName("rethrows a transient data-access failure so Kafka retries it")
        void transientFailure_isRethrown() {
            when(personReplica.findById(PERSON_ID)).thenReturn(Optional.empty());
            when(personReplica.save(any())).thenThrow(new QueryTimeoutException("statement timeout"));

            assertThatExceptionOfType(QueryTimeoutException.class)
                    .isThrownBy(() -> listener.onPeopleContactEvent(personUpdated(5)));

            // Not recorded as processed: the event must be redelivered.
            verify(processedEvents, never()).save(any());
        }
    }
}
