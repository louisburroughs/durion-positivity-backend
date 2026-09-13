package com.positivity.peoplecontact.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.domainevents.peoplecontact.PersonUpdatedV1;
import com.positivity.domainevents.peoplecontact.PersonUpsertRequestedV1;
import com.positivity.peoplecontact.internal.entity.Person;
import com.positivity.peoplecontact.internal.entity.PersonContactPoint;
import com.positivity.peoplecontact.internal.enums.ContactPointType;
import com.positivity.peoplecontact.internal.repository.PartyPostalAddressRepository;
import com.positivity.peoplecontact.internal.repository.PersonContactPointRepository;
import com.positivity.peoplecontact.internal.repository.PersonRepository;
import com.positivity.peoplecontact.internal.repository.ProcessedEventRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The two contact shapes a person upsert command can carry (ADR-0044 §2, #877): the flattened HR
 * set, and pos-customer's full typed list. The typed list went unread until issue #1977, which
 * both dropped the CRM's contact points and let the command that carried them blank the person.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PersonUpsertCommandHandler")
class PersonUpsertCommandHandlerTest {

    private static final UUID PERSON_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a01");
    private static final String EVENT_ID = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a02";

    @Mock
    private PersonRepository personRepository;

    @Mock
    private ProcessedEventRepository processedEventRepository;

    @Mock
    private PersonContactPointRepository personContactPointRepository;

    @Mock
    private PartyPostalAddressRepository partyPostalAddressRepository;

    @Mock
    private PersonWorkPhoneService workPhoneService;

    @Mock
    private PersonEmailService emailService;

    @Mock
    private PeopleContactEventPublisher eventPublisher;

    private PersonUpsertCommandHandler handler;

    @BeforeEach
    void setUp() {
        handler = new PersonUpsertCommandHandler(
                Clock.fixed(Instant.parse("2026-09-13T00:00:00Z"), ZoneOffset.UTC),
                personRepository,
                processedEventRepository,
                personContactPointRepository,
                partyPostalAddressRepository,
                workPhoneService,
                emailService,
                eventPublisher);
        when(personRepository.findById(PERSON_ID)).thenReturn(Optional.empty());
        when(personRepository.save(any(Person.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(partyPostalAddressRepository.findByPartyTypeAndPartyId(any(), any()))
                .thenReturn(Optional.empty());
    }

    @Test
    @DisplayName("the flattened HR shape still writes emails and work phones")
    void appliesTheFlattenedHrShape() {
        handler.apply(
                EVENT_ID,
                new PersonUpsertRequestedV1(
                        PERSON_ID, "Ada", "Lovelace", null, "ada@example.invalid", null, List.of("+15550001")));

        verify(emailService).replaceEmails(PERSON_ID, "ada@example.invalid", null);
        verify(workPhoneService).replaceWorkPhones(PERSON_ID, List.of("+15550001"));
        verify(personContactPointRepository, never()).deleteByPersonId(any());
    }

    @Test
    @DisplayName("a typed contact-point list replaces every contact point the person has")
    void appliesTheTypedContactPointList() {
        handler.apply(
                EVENT_ID,
                new PersonUpsertRequestedV1(
                        PERSON_ID,
                        "Linda",
                        "Guerrero",
                        null,
                        null,
                        null,
                        List.of(),
                        List.of(
                                new PersonUpdatedV1.ContactPointV1("EMAIL", " linda@example.invalid ", true),
                                new PersonUpdatedV1.ContactPointV1("PHONE_MOBILE", "+15550002", false))));

        verify(personContactPointRepository).deleteByPersonId(PERSON_ID);
        // The flattened writers would have deleted exactly what the typed list just wrote.
        verify(emailService, never()).replaceEmails(any(), any(), any());
        verify(workPhoneService, never()).replaceWorkPhones(any(), any());

        ArgumentCaptor<PersonContactPoint> saved = ArgumentCaptor.forClass(PersonContactPoint.class);
        verify(personContactPointRepository, org.mockito.Mockito.times(2)).save(saved.capture());
        assertThat(saved.getAllValues())
                .extracting(
                        PersonContactPoint::getContactType, PersonContactPoint::getValue, PersonContactPoint::isPrimary)
                .containsExactly(
                        tuple(ContactPointType.EMAIL, "linda@example.invalid", true),
                        tuple(ContactPointType.PHONE_MOBILE, "+15550002", false));
    }

    @Test
    @DisplayName("the names on the command are applied alongside the typed contact points")
    void keepsTheNamesThatTravelWithTheTypedList() {
        handler.apply(
                EVENT_ID,
                new PersonUpsertRequestedV1(
                        PERSON_ID,
                        " Linda ",
                        " Guerrero ",
                        null,
                        null,
                        null,
                        List.of(),
                        List.of(new PersonUpdatedV1.ContactPointV1("EMAIL", "linda@example.invalid", true))));

        ArgumentCaptor<Person> saved = ArgumentCaptor.forClass(Person.class);
        verify(personRepository).save(saved.capture());
        assertThat(saved.getValue().getFirstName()).isEqualTo("Linda");
        assertThat(saved.getValue().getLastName()).isEqualTo("Guerrero");
    }

    @Test
    @DisplayName("a contact type this module does not model is dropped, not thrown")
    void dropsAnUnsupportedContactType() {
        handler.apply(
                EVENT_ID,
                new PersonUpsertRequestedV1(
                        PERSON_ID,
                        "Linda",
                        "Guerrero",
                        null,
                        null,
                        null,
                        List.of(),
                        List.of(
                                // pos-customer's taxonomy has a FAX; the person_contact_point
                                // check constraint here does not, and a throw would lose the
                                // identity write with it (the listener drops on any exception).
                                new PersonUpdatedV1.ContactPointV1("FAX", "+15550003", false),
                                new PersonUpdatedV1.ContactPointV1("EMAIL", "linda@example.invalid", true))));

        ArgumentCaptor<PersonContactPoint> saved = ArgumentCaptor.forClass(PersonContactPoint.class);
        verify(personContactPointRepository).save(saved.capture());
        assertThat(saved.getValue().getContactType()).isEqualTo(ContactPointType.EMAIL);
    }

    @Test
    @DisplayName("an empty typed list clears the person's contact points")
    void anEmptyTypedListClearsTheContactPoints() {
        handler.apply(
                EVENT_ID,
                new PersonUpsertRequestedV1(PERSON_ID, "Linda", "Guerrero", null, null, null, List.of(), List.of()));

        verify(personContactPointRepository).deleteByPersonId(PERSON_ID);
        verify(personContactPointRepository, never()).save(any());
    }
}
