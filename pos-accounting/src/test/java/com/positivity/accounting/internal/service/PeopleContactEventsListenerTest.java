package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.positivity.accounting.internal.entity.ExtPeopleContactPerson;
import com.positivity.accounting.internal.entity.ExtPeopleContactUserLink;
import com.positivity.accounting.internal.entity.ProcessedEvent;
import com.positivity.accounting.internal.repository.ExtPeopleContactPersonRepository;
import com.positivity.accounting.internal.repository.ExtPeopleContactUserLinkRepository;
import com.positivity.accounting.internal.repository.ProcessedEventRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.dao.QueryTimeoutException;
import tools.jackson.databind.ObjectMapper;

/**
 * AP reads #2670 AC 9 and AC 11: accounting's people-contact copy applies each fact once, by aggregate id and version,
 * deletes on removal, records the types it ignores, and never logs a name.
 */
@DisplayName("pos-accounting PeopleContactEventsListener — ext_people_contact_* copy (#2670)")
class PeopleContactEventsListenerTest {

    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-09T12:00:00Z"), ZoneOffset.UTC);
    static final UUID PERSON = UUID.fromString("01990000-0000-7000-8000-0000000000b1");
    static final UUID LINK = UUID.fromString("01990000-0000-7000-8000-0000000000c1");
    static final String FIRST = "Dana";
    static final String LAST = "Reyes";

    private final ProcessedEventRepository processed = mock(ProcessedEventRepository.class);
    private final ExtPeopleContactPersonRepository persons = mock(ExtPeopleContactPersonRepository.class);
    private final ExtPeopleContactUserLinkRepository links = mock(ExtPeopleContactUserLinkRepository.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger root = (Logger) LoggerFactory.getLogger("com.positivity");
    private Level before;
    private PeopleContactEventsListener listener;

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = new ObjectMapper();
        listener = new PeopleContactEventsListener(
                mapper, new PeopleContactReplica(CLOCK, mapper, processed, persons, links));
        when(persons.findById(any())).thenReturn(Optional.empty());
        when(links.findById(any())).thenReturn(Optional.empty());
        before = root.getLevel();
        logs.start();
        root.addAppender(logs);
        root.setLevel(Level.DEBUG);
    }

    @AfterEach
    void tearDown() {
        root.detachAppender(logs);
        root.setLevel(before);
    }

    static String personUpdated(String eventId, long version, String first, String last) {
        return """
            {"eventId":"%s","eventType":"people-contact.person.updated","schemaVersion":1,
             "aggregateId":"%s","aggregateVersion":%d,"occurredAtUtc":"2026-10-09T11:59:00Z",
             "sourceService":"pos-people-contact",
             "payload":{"personId":"%s","firstName":%s,"lastName":%s,"preferredName":"Dee",
                        "contactPoints":[{"contactType":"EMAIL","value":"dana@example.test","primary":true}],
                        "postalAddress":null}}
            """.formatted(eventId, PERSON, version, PERSON, json(first), json(last));
    }

    static String personDeleted(String eventId) {
        return """
            {"eventId":"%s","eventType":"people-contact.person.deleted","aggregateId":"%s","aggregateVersion":9,
             "payload":{"personId":"%s"}}
            """.formatted(eventId, PERSON, PERSON);
    }

    static String linkUpdated(String eventId, long version, String username, String status) {
        return """
            {"eventId":"%s","eventType":"people-contact.user-person-link.updated","aggregateId":"%s",
             "aggregateVersion":%d,
             "payload":{"linkId":"%s","personId":"%s","username":"%s","status":"%s","linkType":"EMPLOYEE"}}
            """.formatted(eventId, LINK, version, LINK, PERSON, username, status);
    }

    static String linkRemoved(String eventId) {
        return """
            {"eventId":"%s","eventType":"people-contact.user-person-link.removed","aggregateId":"%s",
             "aggregateVersion":5,
             "payload":{"linkId":"%s","personId":"%s","username":"controller.cfo"}}
            """.formatted(eventId, LINK, LINK, PERSON);
    }

    private static String json(String value) {
        return value == null ? "null" : "\"" + value + "\"";
    }

    private void assertNoNameLogged() {
        assertThat(logs.list)
                .filteredOn(event -> event.getLevel().isGreaterOrEqual(Level.INFO))
                .allSatisfy(event -> assertThat(event.getFormattedMessage())
                        .doesNotContain(FIRST)
                        .doesNotContain(LAST));
    }

    @Test
    @DisplayName("person.updated writes first and last name only, by person id and version, and marks the event")
    void personCopied() {
        listener.onPeopleContactEvent(personUpdated("e-1", 100, FIRST, LAST));

        ArgumentCaptor<ExtPeopleContactPerson> copy = ArgumentCaptor.forClass(ExtPeopleContactPerson.class);
        verify(persons).save(copy.capture());
        assertThat(copy.getValue().getPersonId()).isEqualTo(PERSON);
        assertThat(copy.getValue().getFirstName()).isEqualTo(FIRST);
        assertThat(copy.getValue().getLastName()).isEqualTo(LAST);
        assertThat(copy.getValue().getAggregateVersion()).isEqualTo(100);
        assertThat(copy.getValue().toString()).doesNotContain(FIRST).doesNotContain(LAST);
        ArgumentCaptor<ProcessedEvent> mark = ArgumentCaptor.forClass(ProcessedEvent.class);
        verify(processed).save(mark.capture());
        assertThat(mark.getValue().getOwner()).isEqualTo("people-contact");
        assertNoNameLogged();
    }

    @Test
    @DisplayName("AC 9: a person.updated older than the stored row leaves it unchanged; an equal one applies")
    void olderPersonFactIgnored() {
        ExtPeopleContactPerson held = ExtPeopleContactPerson.builder()
                .personId(PERSON)
                .firstName(FIRST)
                .lastName(LAST)
                .aggregateVersion(200)
                .build();
        when(persons.findById(PERSON)).thenReturn(Optional.of(held));

        listener.onPeopleContactEvent(personUpdated("e-old", 199, "Old", "Name"));

        verify(persons, never()).save(any());
        assertThat(held.getFirstName()).isEqualTo(FIRST);
        verify(processed, times(1)).save(any());

        listener.onPeopleContactEvent(personUpdated("e-same", 200, "Dana", "Reyes-Ortiz"));
        verify(persons, times(1)).save(any());
        assertThat(held.getLastName()).isEqualTo("Reyes-Ortiz");
    }

    @Test
    @DisplayName("AC 9: a redelivered eventId writes nothing")
    void redeliveryWritesNothing() {
        when(processed.existsById("e-1")).thenReturn(false, true);

        listener.onPeopleContactEvent(personUpdated("e-1", 100, FIRST, LAST));
        listener.onPeopleContactEvent(personUpdated("e-1", 100, FIRST, LAST));

        verify(persons, times(1)).save(any());
        verify(processed, times(1)).save(any());
    }

    @Test
    @DisplayName("user-person-link.updated writes the link with its username and status; an older one is ignored")
    void linkCopied() {
        listener.onPeopleContactEvent(linkUpdated("e-l1", 10, "controller.cfo", "ACTIVE"));

        ArgumentCaptor<ExtPeopleContactUserLink> copy = ArgumentCaptor.forClass(ExtPeopleContactUserLink.class);
        verify(links).save(copy.capture());
        assertThat(copy.getValue().getLinkId()).isEqualTo(LINK);
        assertThat(copy.getValue().getPersonId()).isEqualTo(PERSON);
        assertThat(copy.getValue().getUsername()).isEqualTo("controller.cfo");
        assertThat(copy.getValue().getStatus()).isEqualTo("ACTIVE");

        when(links.findById(LINK)).thenReturn(Optional.of(copy.getValue()));
        listener.onPeopleContactEvent(linkUpdated("e-l0", 9, "controller.cfo", "INACTIVE"));
        assertThat(copy.getValue().getStatus()).isEqualTo("ACTIVE");
        verify(links, times(1)).save(any());
    }

    @Test
    @DisplayName("AC 9 / review B5: user-person-link.removed tombstones the link (REMOVED, its version) and"
            + " person.deleted tombstones the person (deleted, names cleared, its version); nothing is hard-deleted")
    void removalsTombstone() {
        ExtPeopleContactUserLink link = ExtPeopleContactUserLink.builder()
                .linkId(LINK)
                .personId(PERSON)
                .username("controller.cfo")
                .status("ACTIVE")
                .aggregateVersion(1)
                .build();
        ExtPeopleContactPerson person = ExtPeopleContactPerson.builder()
                .personId(PERSON)
                .firstName(FIRST)
                .lastName(LAST)
                .aggregateVersion(1)
                .build();
        when(links.findById(LINK)).thenReturn(Optional.of(link));
        when(persons.findById(PERSON)).thenReturn(Optional.of(person));

        listener.onPeopleContactEvent(linkRemoved("e-r"));
        listener.onPeopleContactEvent(personDeleted("e-d"));

        verify(links, never()).delete(any());
        verify(persons, never()).delete(any());
        verify(links).save(link);
        verify(persons).save(person);
        assertThat(link.getStatus()).isEqualTo(ExtPeopleContactUserLink.REMOVED);
        assertThat(link.getAggregateVersion()).isEqualTo(5);
        assertThat(person.isDeleted()).isTrue();
        assertThat(person.getFirstName()).isNull();
        assertThat(person.getLastName()).isNull();
        assertThat(person.getAggregateVersion()).isEqualTo(9);
        verify(processed, times(2)).save(any());
    }

    @Test
    @DisplayName("review B5: removed at v5, then a late user-person-link.updated at v4, leaves no ACTIVE link")
    void lateUpdateAfterRemovalDoesNotRevive() {
        // The removal arrives first, for a link the copy never saw: the tombstone is still written.
        listener.onPeopleContactEvent(linkRemoved("e-r5"));
        ArgumentCaptor<ExtPeopleContactUserLink> tombstone = ArgumentCaptor.forClass(ExtPeopleContactUserLink.class);
        verify(links).save(tombstone.capture());
        assertThat(tombstone.getValue().getStatus()).isEqualTo(ExtPeopleContactUserLink.REMOVED);
        assertThat(tombstone.getValue().getAggregateVersion()).isEqualTo(5);
        when(links.findById(LINK)).thenReturn(Optional.of(tombstone.getValue()));

        listener.onPeopleContactEvent(linkUpdated("e-u4", 4, "controller.cfo", "ACTIVE"));

        verify(links, times(1)).save(any());
        assertThat(tombstone.getValue().getStatus()).isEqualTo(ExtPeopleContactUserLink.REMOVED);
    }

    @Test
    @DisplayName("review B5: deleted at v9, then a late person.updated at v8, leaves the person deleted and nameless")
    void lateUpdateAfterDeletionDoesNotRevive() {
        listener.onPeopleContactEvent(personDeleted("e-d9"));
        ArgumentCaptor<ExtPeopleContactPerson> tombstone = ArgumentCaptor.forClass(ExtPeopleContactPerson.class);
        verify(persons).save(tombstone.capture());
        assertThat(tombstone.getValue().isDeleted()).isTrue();
        when(persons.findById(PERSON)).thenReturn(Optional.of(tombstone.getValue()));

        listener.onPeopleContactEvent(personUpdated("e-u8", 8, FIRST, LAST));

        verify(persons, times(1)).save(any());
        assertThat(tombstone.getValue().isDeleted()).isTrue();
        assertThat(tombstone.getValue().getFirstName()).isNull();
    }

    @Test
    @DisplayName("AC 9: an ignored people-contact type is recorded as processed and writes nothing else")
    void ignoredTypeRecorded() {
        listener.onPeopleContactEvent("""
            {"eventId":"e-org","eventType":"people-contact.organization-address.updated","aggregateVersion":1,
             "payload":{}}
            """);

        verify(persons, never()).save(any());
        verify(links, never()).save(any());
        ArgumentCaptor<ProcessedEvent> mark = ArgumentCaptor.forClass(ProcessedEvent.class);
        verify(processed).save(mark.capture());
        assertThat(mark.getValue().getEventId()).isEqualTo("e-org");
    }

    @Test
    @DisplayName("AC 11: a malformed payload is marked processed, and nothing logged carries a name")
    void malformedPayloadLogsNoName() {
        listener.onPeopleContactEvent(
                personUpdated("e-bad", 1, FIRST, LAST).replace("\"personId\":\"", "\"personId\":\"x"));
        listener.onPeopleContactEvent("not json " + FIRST + " " + LAST);

        verify(persons, never()).save(any());
        verify(processed, times(1)).save(any());
        assertNoNameLogged();
        assertThat(logs.list)
                .allSatisfy(event -> assertThat(event.getFormattedMessage()).doesNotContain(FIRST));
    }

    @Test
    @DisplayName("a transient database failure propagates for container retry, nothing marked")
    void transientFailurePropagates() {
        when(persons.findById(any())).thenThrow(new QueryTimeoutException("timeout"));

        assertThatExceptionOfType(QueryTimeoutException.class)
                .isThrownBy(() -> listener.onPeopleContactEvent(personUpdated("e-2", 1, FIRST, LAST)));
        verify(processed, never()).save(any());
    }

    @Test
    @DisplayName("the ledger read for the manifest is scoped to the people-contact owner")
    void ledgerScopedToOwner() {
        PeopleContactReplica replica = new PeopleContactReplica(CLOCK, new ObjectMapper(), processed, persons, links);
        UUID tenant = UUID.fromString("01990000-0000-7000-8000-0000000000f1");
        when(processed.findEventIdsInRangeForOwner("people-contact", tenant, "a", "b"))
                .thenReturn(List.of("e-1"));

        assertThat(replica.receivedEventIds(tenant, "a", "b")).containsExactly("e-1");
    }
}
