package com.positivity.peoplecontact.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.peoplecontact.internal.dto.Person;
import com.positivity.peoplecontact.internal.dto.ResolvePersonRequest;
import com.positivity.peoplecontact.internal.dto.ResolvePersonResponse;
import com.positivity.peoplecontact.internal.entity.PersonContactPoint;
import com.positivity.peoplecontact.internal.enums.ContactPointType;
import com.positivity.peoplecontact.internal.enums.PartyType;
import com.positivity.peoplecontact.internal.exception.PeopleContactValidationException;
import com.positivity.peoplecontact.internal.exception.PersonHasLinkedUsersException;
import com.positivity.peoplecontact.internal.repository.PartyPostalAddressRepository;
import com.positivity.peoplecontact.internal.repository.PersonContactPointRepository;
import com.positivity.peoplecontact.internal.repository.PersonRepository;
import com.positivity.peoplecontact.internal.repository.UserPersonLinkRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Person authority behaviour (ADR-0015 I2, ADR-0044 §6): identity reads join emails, phones and
 * the security-owned username; {@code resolvePerson} scores candidates before falling back to a
 * create; and deletion refuses to orphan a linked user.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("PersonServiceImpl")
class PersonServiceImplTest {

    private static final UUID PERSON_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3fdd01");
    private static final UUID OTHER_PERSON_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3fdd02");
    private static final int DEFAULT_THRESHOLD = 30;

    @Mock
    private PersonRepository personRepository;

    @Mock
    private PersonContactPointRepository personContactPointRepository;

    @Mock
    private PartyPostalAddressRepository partyPostalAddressRepository;

    @Mock
    private UserPersonLinkRepository userPersonLinkRepository;

    @Mock
    private PeopleContactEventPublisher eventPublisher;

    @Mock
    private PersonWorkPhoneService workPhoneService;

    @Mock
    private PersonEmailService emailService;

    @Mock
    private PersonUsernameService usernameService;

    private PersonServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new PersonServiceImpl(
                personRepository,
                personContactPointRepository,
                partyPostalAddressRepository,
                userPersonLinkRepository,
                eventPublisher,
                workPhoneService,
                emailService,
                usernameService);
        ReflectionTestUtils.setField(service, "defaultMatchingThreshold", DEFAULT_THRESHOLD);
        when(emailService.getEmails(any())).thenReturn(new PersonEmailService.EmailPair(null, null));
        when(workPhoneService.getWorkPhones(any(UUID.class))).thenReturn(List.of());
        when(usernameService.usernamesByPersonId(any())).thenReturn(Map.of());
        when(personRepository.save(any())).thenAnswer(invocation -> {
            com.positivity.peoplecontact.internal.entity.Person entity = invocation.getArgument(0);
            if (entity.getId() == null) {
                entity.setId(PERSON_ID);
            }
            return entity;
        });
        when(partyPostalAddressRepository.findByPartyTypeAndPartyId(PartyType.PERSON, PERSON_ID))
                .thenReturn(Optional.empty());
    }

    private static com.positivity.peoplecontact.internal.entity.Person entity(
            UUID id, String firstName, String lastName) {
        com.positivity.peoplecontact.internal.entity.Person person =
                new com.positivity.peoplecontact.internal.entity.Person();
        person.setId(id);
        person.setFirstName(firstName);
        person.setLastName(lastName);
        person.setUpdatedAt(Instant.parse("2026-03-01T12:00:00Z"));
        return person;
    }

    @Nested
    @DisplayName("reads")
    class Reads {

        @Test
        void attachesTheSecurityOwnedUsernameToEveryDirectoryRow() {
            when(personRepository.findAll(any(Specification.class)))
                    .thenReturn(List.of(entity(PERSON_ID, "Jane", "Smith")));
            when(usernameService.usernamesByPersonId(List.of(PERSON_ID))).thenReturn(Map.of(PERSON_ID, "jane.smith"));
            when(emailService.getEmails(PERSON_ID))
                    .thenReturn(new PersonEmailService.EmailPair("jane@example.invalid", "j2@example.invalid"));
            when(workPhoneService.getWorkPhones(PERSON_ID)).thenReturn(List.of("5550100"));

            List<Person> people = service.getAllPeople("smith");

            assertThat(people).hasSize(1);
            assertThat(people.get(0).getUsername()).isEqualTo("jane.smith");
            assertThat(people.get(0).getPrimaryEmail()).isEqualTo("jane@example.invalid");
            assertThat(people.get(0).getSecondaryEmail()).isEqualTo("j2@example.invalid");
            assertThat(people.get(0).getPhoneNumbers()).containsExactly("5550100");
        }

        @Test
        void readsASinglePersonWithTheirUsername() {
            when(personRepository.findById(PERSON_ID)).thenReturn(Optional.of(entity(PERSON_ID, "Jane", "Smith")));
            when(usernameService.usernameForPerson(PERSON_ID)).thenReturn("jane.smith");

            Person person = service.getPersonById(PERSON_ID).orElseThrow();

            assertThat(person.getFirstName()).isEqualTo("Jane");
            assertThat(person.getUsername()).isEqualTo("jane.smith");
        }

        @Test
        void returnsEmptyForAnUnknownPerson() {
            when(personRepository.findById(PERSON_ID)).thenReturn(Optional.empty());

            assertThat(service.getPersonById(PERSON_ID)).isEmpty();
        }

        @Test
        void batchesContactPointsWhenReadingSeveralPeople() {
            List<UUID> ids = List.of(PERSON_ID, OTHER_PERSON_ID);
            when(personRepository.findAllById(ids))
                    .thenReturn(List.of(entity(PERSON_ID, "Jane", "Smith"), entity(OTHER_PERSON_ID, "Rob", "Smith")));
            when(personContactPointRepository.findByPersonIdIn(ids))
                    .thenReturn(List.of(PersonContactPoint.builder()
                            .personId(PERSON_ID)
                            .contactType(ContactPointType.EMAIL)
                            .value("jane@example.invalid")
                            .isPrimary(true)
                            .build()));
            when(usernameService.usernamesByPersonId(ids)).thenReturn(Map.of(PERSON_ID, "jane.smith"));

            List<Person> people = service.getPeopleByIds(ids);

            assertThat(people).hasSize(2);
            assertThat(people.get(0).getContactPoints()).hasSize(1);
            assertThat(people.get(0).getContactPoints().get(0).getValue()).isEqualTo("jane@example.invalid");
            assertThat(people.get(0).getContactPoints().get(0).isPrimary()).isTrue();
            // A person with no contact points gets an empty list, not null.
            assertThat(people.get(1).getContactPoints()).isEmpty();
            assertThat(people.get(1).getUsername()).isNull();
        }

        @Test
        void readsNothingForAnEmptyIdSet() {
            assertThat(service.getPeopleByIds(List.of())).isEmpty();
            verify(personRepository, never()).findAllById(any());
        }
    }

    @Nested
    @DisplayName("replaceContactPoints")
    class ReplaceContactPoints {

        @Test
        void replacesTheStoredSetAndPublishesTheUpdate() {
            when(personRepository.findById(PERSON_ID)).thenReturn(Optional.of(entity(PERSON_ID, "Jane", "Smith")));
            Person.ContactPointDto contactPoint = new Person.ContactPointDto();
            contactPoint.setContactType(ContactPointType.EMAIL);
            contactPoint.setValue("jane@example.invalid");
            contactPoint.setPrimary(true);

            service.replaceContactPoints(PERSON_ID, List.of(contactPoint));

            verify(personContactPointRepository).deleteByPersonId(PERSON_ID);
            ArgumentCaptor<List<PersonContactPoint>> saved = ArgumentCaptor.captor();
            verify(personContactPointRepository).saveAll(saved.capture());
            assertThat(saved.getValue()).hasSize(1);
            assertThat(saved.getValue().get(0).getValue()).isEqualTo("jane@example.invalid");
            verify(eventPublisher).publishPersonUpdated(any(), any(), any());
        }

        @Test
        void clearsTheSetWithoutPublishingWhenGivenNothingToStore() {
            service.replaceContactPoints(PERSON_ID, List.of());

            verify(personContactPointRepository).deleteByPersonId(PERSON_ID);
            verify(personContactPointRepository, never()).saveAll(any());
            verify(eventPublisher, never()).publishPersonUpdated(any(), any(), any());
        }

        @Test
        void dropsContactPointsMissingATypeOrAValue() {
            when(personRepository.findById(PERSON_ID)).thenReturn(Optional.of(entity(PERSON_ID, "Jane", "Smith")));
            Person.ContactPointDto noType = new Person.ContactPointDto();
            noType.setValue("jane@example.invalid");
            Person.ContactPointDto noValue = new Person.ContactPointDto();
            noValue.setContactType(ContactPointType.EMAIL);

            service.replaceContactPoints(PERSON_ID, List.of(noType, noValue));

            ArgumentCaptor<List<PersonContactPoint>> saved = ArgumentCaptor.captor();
            verify(personContactPointRepository).saveAll(saved.capture());
            assertThat(saved.getValue()).isEmpty();
        }
    }

    @Nested
    @DisplayName("savePerson")
    class SavePerson {

        @Test
        void persistsNamesLocallyAndDelegatesContactsToTheirOwnServices() {
            when(personRepository.findById(PERSON_ID)).thenReturn(Optional.of(entity(PERSON_ID, "Jane", "Smith")));
            when(usernameService.usernameForPerson(PERSON_ID)).thenReturn("jane.smith");
            Person request = new Person();
            request.setFirstName("Jane");
            request.setLastName("Smith");
            request.setPrimaryEmail("jane@example.invalid");
            request.setPhoneNumbers(List.of("5550100"));

            Person saved = service.savePerson(request);

            verify(workPhoneService).replaceWorkPhones(PERSON_ID, List.of("5550100"));
            verify(emailService).replaceEmails(PERSON_ID, "jane@example.invalid", null);
            verify(eventPublisher).publishPersonUpdated(any(), any(), any());
            assertThat(saved.getUsername()).isEqualTo("jane.smith");
        }

        @Test
        void treatsMissingPhoneNumbersAsAnEmptyList() {
            when(personRepository.findById(PERSON_ID)).thenReturn(Optional.of(entity(PERSON_ID, "Jane", "Smith")));
            Person request = new Person();
            request.setFirstName("Jane");

            service.savePerson(request);

            verify(workPhoneService).replaceWorkPhones(PERSON_ID, List.of());
        }
    }

    @Nested
    @DisplayName("resolvePerson")
    class ResolvePerson {

        @Test
        void matchesAnExistingPersonOnEmailAlone() {
            when(emailService.findPersonIdsByEmail("jane@example.invalid")).thenReturn(List.of(PERSON_ID));
            when(personRepository.findAllById(List.of(PERSON_ID)))
                    .thenReturn(List.of(entity(PERSON_ID, "Jane", "Smith")));
            when(emailService.getEmails(PERSON_ID))
                    .thenReturn(new PersonEmailService.EmailPair("jane@example.invalid", null));

            ResolvePersonResponse response = service.resolvePerson(ResolvePersonRequest.builder()
                    .email("  Jane@Example.Invalid ")
                    .build());

            assertThat(response.isMatchedExisting()).isTrue();
            assertThat(response.getPersonId()).isEqualTo(PERSON_ID);
            assertThat(response.getScore()).isEqualTo(60);
            assertThat(response.getThresholdApplied()).isEqualTo(DEFAULT_THRESHOLD);
            assertThat(response.getMatchedBy()).containsExactly("EMAIL");
            verify(personRepository, never()).save(any());
        }

        @Test
        void addsUpEverySignalThatPointsAtTheSamePerson() {
            when(emailService.findPersonIdsByEmail("jane@example.invalid")).thenReturn(List.of(PERSON_ID));
            when(workPhoneService.findPersonIdsByWorkPhone("+15550100")).thenReturn(List.of(PERSON_ID));
            when(personRepository.findAllById(List.of(PERSON_ID)))
                    .thenReturn(List.of(entity(PERSON_ID, "Jane", "Smith")));
            when(personRepository.findByLastNameIgnoreCase("Smith"))
                    .thenReturn(List.of(entity(PERSON_ID, "Jane", "Smith")));
            when(personRepository.findByFirstNameIgnoreCase("Jane"))
                    .thenReturn(List.of(entity(PERSON_ID, "Jane", "Smith")));

            ResolvePersonResponse response = service.resolvePerson(ResolvePersonRequest.builder()
                    .email("jane@example.invalid")
                    .phone("+1 (555) 0100")
                    .lastName("Smith")
                    .firstName("Jane")
                    .build());

            assertThat(response.getScore()).isEqualTo(100);
            assertThat(response.getMatchedBy()).containsExactly("EMAIL", "PHONE", "LAST_NAME", "FIRST_NAME");
        }

        @Test
        void createsAPersonWhenTheBestCandidateIsBelowTheThreshold() {
            when(personRepository.findByLastNameIgnoreCase("Smith"))
                    .thenReturn(List.of(entity(OTHER_PERSON_ID, "Rob", "Smith")));
            when(personRepository.findById(PERSON_ID)).thenReturn(Optional.of(entity(PERSON_ID, null, "Smith")));

            ResolvePersonResponse response = service.resolvePerson(
                    ResolvePersonRequest.builder().lastName("Smith").build());

            // LAST_NAME alone scores 10, under the default threshold of 30.
            assertThat(response.isMatchedExisting()).isFalse();
            assertThat(response.getScore()).isZero();
            assertThat(response.getMatchedBy()).containsExactly("CREATED");
            verify(workPhoneService).replaceWorkPhones(PERSON_ID, List.of());
            verify(emailService).replaceEmails(PERSON_ID, null, null);
        }

        @Test
        void carriesTheNormalizedPhoneOntoANewlyCreatedPerson() {
            when(personRepository.findById(PERSON_ID)).thenReturn(Optional.of(entity(PERSON_ID, "Jane", null)));

            ResolvePersonResponse response = service.resolvePerson(ResolvePersonRequest.builder()
                    .firstName("Jane")
                    .phone("555-0100")
                    .build());

            assertThat(response.isMatchedExisting()).isFalse();
            assertThat(response.getPhoneNumbers()).containsExactly("5550100");
            verify(workPhoneService).replaceWorkPhones(PERSON_ID, List.of("5550100"));
        }

        @Test
        void honoursAnExplicitThresholdOverride() {
            when(personRepository.findByLastNameIgnoreCase("Smith"))
                    .thenReturn(List.of(entity(PERSON_ID, "Jane", "Smith")));

            ResolvePersonResponse response = service.resolvePerson(ResolvePersonRequest.builder()
                    .lastName("Smith")
                    .threshold(5)
                    .build());

            assertThat(response.isMatchedExisting()).isTrue();
            assertThat(response.getThresholdApplied()).isEqualTo(5);
        }

        @Test
        void clampsANegativeThresholdToZero() {
            when(personRepository.findByLastNameIgnoreCase("Smith"))
                    .thenReturn(List.of(entity(PERSON_ID, "Jane", "Smith")));

            assertThat(service.resolvePerson(ResolvePersonRequest.builder()
                                    .lastName("Smith")
                                    .threshold(-10)
                                    .build())
                            .getThresholdApplied())
                    .isZero();
        }

        @Test
        void picksTheHigherScoringCandidateWhenSeveralMatch() {
            when(emailService.findPersonIdsByEmail("jane@example.invalid")).thenReturn(List.of(PERSON_ID));
            when(personRepository.findAllById(List.of(PERSON_ID)))
                    .thenReturn(List.of(entity(PERSON_ID, "Jane", "Smith")));
            when(personRepository.findByLastNameIgnoreCase("Smith"))
                    .thenReturn(List.of(entity(OTHER_PERSON_ID, "Rob", "Smith")));

            ResolvePersonResponse response = service.resolvePerson(ResolvePersonRequest.builder()
                    .email("jane@example.invalid")
                    .lastName("Smith")
                    .build());

            assertThat(response.getPersonId()).isEqualTo(PERSON_ID);
            assertThat(response.getScore()).isEqualTo(60);
        }

        @Test
        void ignoresAPhoneThatCarriesNoDigits() {
            when(personRepository.findById(PERSON_ID)).thenReturn(Optional.of(entity(PERSON_ID, "Jane", null)));

            ResolvePersonResponse response = service.resolvePerson(ResolvePersonRequest.builder()
                    .firstName("Jane")
                    .phone("---")
                    .build());

            assertThat(response.getPhoneNumbers()).isEmpty();
        }

        @Test
        void refusesARequestWithNoIdentifyingFieldAtAll() {
            ResolvePersonRequest request = ResolvePersonRequest.builder()
                    .email("   ")
                    .phone(null)
                    .lastName("")
                    .build();

            assertThatThrownBy(() -> service.resolvePerson(request))
                    .isInstanceOf(PeopleContactValidationException.class)
                    .hasMessageContaining("At least one of email, phone, lastName, or firstName");
        }
    }

    @Nested
    @DisplayName("deletePerson")
    class DeletePerson {

        @Test
        void removesContactPointsAndTheAddressBeforePublishingTheDeletion() {
            when(userPersonLinkRepository.existsByPerson_Id(PERSON_ID)).thenReturn(false);

            service.deletePerson(PERSON_ID);

            verify(personContactPointRepository).deleteByPersonId(PERSON_ID);
            verify(partyPostalAddressRepository).deleteByPartyTypeAndPartyId(PartyType.PERSON, PERSON_ID);
            verify(personRepository).deleteById(PERSON_ID);
            verify(personRepository).flush();
            verify(eventPublisher).publishPersonDeleted(PERSON_ID);
        }

        @Test
        void refusesToDeleteAPersonWithALinkedUser() {
            when(userPersonLinkRepository.existsByPerson_Id(PERSON_ID)).thenReturn(true);

            assertThatThrownBy(() -> service.deletePerson(PERSON_ID)).isInstanceOf(PersonHasLinkedUsersException.class);
            verify(personRepository, never()).deleteById(any(UUID.class));
        }

        @Test
        void turnsALinkCreatedDuringTheDeleteIntoTheSameConflict() {
            when(userPersonLinkRepository.existsByPerson_Id(PERSON_ID)).thenReturn(false);
            org.mockito.Mockito.doThrow(new DataIntegrityViolationException("fk violation"))
                    .when(personRepository)
                    .flush();

            assertThatThrownBy(() -> service.deletePerson(PERSON_ID)).isInstanceOf(PersonHasLinkedUsersException.class);
            verify(eventPublisher, never()).publishPersonDeleted(any());
        }
    }
}
