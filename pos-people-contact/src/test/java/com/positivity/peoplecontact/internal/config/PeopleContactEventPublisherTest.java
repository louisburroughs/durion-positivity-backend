package com.positivity.peoplecontact.internal.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.domainevents.DomainEventEnvelope;
import com.positivity.domainevents.peoplecontact.PersonDeletedV1;
import com.positivity.domainevents.peoplecontact.PersonUpdatedV1;
import com.positivity.domainevents.peoplecontact.UserPersonLinkRemovedV1;
import com.positivity.domainevents.peoplecontact.UserPersonLinkUpdatedV1;
import com.positivity.peoplecontact.internal.entity.Person;
import com.positivity.peoplecontact.internal.entity.PersonContactPoint;
import com.positivity.peoplecontact.internal.entity.UserPersonLink;
import com.positivity.peoplecontact.internal.enums.ContactPointType;
import com.positivity.peoplecontact.internal.enums.UserLinkStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Unit tests for {@link PeopleContactEventPublisher} (ADR-0044 §6, #874).
 */
class PeopleContactEventPublisherTest {

    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2026-07-12T12:00:00Z"), ZoneOffset.UTC);
    private static final UUID PERSON_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID LINK_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private final OutboxEventWriter writer = mock(OutboxEventWriter.class);

    @SuppressWarnings("unchecked")
    private final ObjectProvider<OutboxEventWriter> writerProvider = mock(ObjectProvider.class);

    private PeopleContactEventPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new PeopleContactEventPublisher(TEST_CLOCK, writerProvider, "people-contact.events.v1");
    }

    private Person person() {
        return Person.builder()
                .id(PERSON_ID)
                .firstName("Jane")
                .lastName("Smith")
                .build();
    }

    private UserPersonLink link() {
        UserPersonLink link = new UserPersonLink();
        link.setId(LINK_ID);
        link.setUsername("jane.smith");
        link.setPerson(person());
        link.setStatus(UserLinkStatus.ACTIVE);
        link.setLinkType("PRIMARY");
        return link;
    }

    @SuppressWarnings("rawtypes")
    private DomainEventEnvelope capturePublished(String expectedTopic) {
        ArgumentCaptor<String> topic = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<DomainEventEnvelope> envelope = ArgumentCaptor.forClass(DomainEventEnvelope.class);
        verify(writer).publish(topic.capture(), envelope.capture());
        assertThat(topic.getValue()).isEqualTo(expectedTopic);
        return envelope.getValue();
    }

    @Test
    @DisplayName("Queues a person.updated envelope carrying the contact-point snapshot")
    void queuesPersonUpdated() {
        when(writerProvider.getIfAvailable()).thenReturn(writer);
        PersonContactPoint email = PersonContactPoint.builder()
                .personId(PERSON_ID)
                .contactType(ContactPointType.EMAIL)
                .value("jane@example.com")
                .isPrimary(true)
                .build();

        publisher.publishPersonUpdated(person(), List.of(email));

        DomainEventEnvelope<?> envelope = capturePublished("people-contact.events.v1");
        assertThat(envelope.eventType()).isEqualTo(PersonUpdatedV1.EVENT_TYPE);
        assertThat(envelope.aggregateId()).isEqualTo(PERSON_ID);
        assertThat(envelope.aggregateVersion()).isEqualTo(TEST_CLOCK.instant().toEpochMilli());
        assertThat(envelope.sourceService()).isEqualTo("pos-people-contact");
        PersonUpdatedV1 payload = (PersonUpdatedV1) envelope.payload();
        assertThat(payload.personId()).isEqualTo(PERSON_ID);
        assertThat(payload.firstName()).isEqualTo("Jane");
        assertThat(payload.contactPoints())
                .containsExactly(new PersonUpdatedV1.ContactPointV1("EMAIL", "jane@example.com", true));
    }

    @Test
    @DisplayName("Queues a person.deleted envelope keyed by the person id")
    void queuesPersonDeleted() {
        when(writerProvider.getIfAvailable()).thenReturn(writer);

        publisher.publishPersonDeleted(PERSON_ID);

        DomainEventEnvelope<?> envelope = capturePublished("people-contact.events.v1");
        assertThat(envelope.eventType()).isEqualTo(PersonDeletedV1.EVENT_TYPE);
        assertThat(envelope.aggregateId()).isEqualTo(PERSON_ID);
        assertThat(((PersonDeletedV1) envelope.payload()).personId()).isEqualTo(PERSON_ID);
    }

    @Test
    @DisplayName("Queues a user-person-link.updated envelope keyed by the link id")
    void queuesLinkUpdated() {
        when(writerProvider.getIfAvailable()).thenReturn(writer);

        publisher.publishLinkUpdated(link());

        DomainEventEnvelope<?> envelope = capturePublished("people-contact.events.v1");
        assertThat(envelope.eventType()).isEqualTo(UserPersonLinkUpdatedV1.EVENT_TYPE);
        assertThat(envelope.aggregateId()).isEqualTo(LINK_ID);
        UserPersonLinkUpdatedV1 payload = (UserPersonLinkUpdatedV1) envelope.payload();
        assertThat(payload.personId()).isEqualTo(PERSON_ID);
        assertThat(payload.username()).isEqualTo("jane.smith");
        assertThat(payload.status()).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("Queues a user-person-link.removed envelope keyed by the link id")
    void queuesLinkRemoved() {
        when(writerProvider.getIfAvailable()).thenReturn(writer);

        publisher.publishLinkRemoved(link());

        DomainEventEnvelope<?> envelope = capturePublished("people-contact.events.v1");
        assertThat(envelope.eventType()).isEqualTo(UserPersonLinkRemovedV1.EVENT_TYPE);
        assertThat(envelope.aggregateId()).isEqualTo(LINK_ID);
        UserPersonLinkRemovedV1 payload = (UserPersonLinkRemovedV1) envelope.payload();
        assertThat(payload.username()).isEqualTo("jane.smith");
        assertThat(payload.personId()).isEqualTo(PERSON_ID);
    }

    @Test
    @DisplayName("No-op when the Kafka feature flag is off (writer bean absent)")
    void noopWithoutWriter() {
        when(writerProvider.getIfAvailable()).thenReturn(null);

        publisher.publishPersonUpdated(person(), List.of());
        publisher.publishPersonDeleted(PERSON_ID);
        publisher.publishLinkUpdated(link());
        publisher.publishLinkRemoved(link());

        verify(writer, never()).publish(any(), any());
    }
}
