package com.positivity.peoplecontact.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.peoplecontact.internal.dto.LinkUserToPersonRequest;
import com.positivity.peoplecontact.internal.dto.PersonResponse;
import com.positivity.peoplecontact.internal.dto.UserPersonLinkResponse;
import com.positivity.peoplecontact.internal.entity.Person;
import com.positivity.peoplecontact.internal.entity.UserPersonLink;
import com.positivity.peoplecontact.internal.exception.PersonNotFoundException;
import com.positivity.peoplecontact.internal.exception.UserAlreadyLinkedException;
import com.positivity.peoplecontact.internal.exception.UserPersonLinkNotFoundException;
import com.positivity.peoplecontact.internal.repository.PersonRepository;
import com.positivity.peoplecontact.internal.repository.UserPersonLinkRepository;
import com.positivity.peoplecontact.internal.service.UserPersonLinkServiceImpl;
import com.positivity.security.common.SecurityContextHelper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UserPersonLinkServiceTest {

    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);

    @Mock
    private UserPersonLinkRepository linkRepository;

    @Mock
    private PersonRepository personRepository;

    @Mock
    private com.positivity.peoplecontact.internal.service.PersonWorkPhoneService workPhoneService;

    @Mock
    private com.positivity.peoplecontact.internal.service.PersonEmailService emailService;

    @Mock
    private com.positivity.peoplecontact.internal.service.PersonUsernameService usernameService;

    @Mock
    private com.positivity.peoplecontact.internal.service.PeopleContactEventPublisher eventPublisher;

    private UserPersonLinkServiceImpl service;

    // Fixed test values for deterministic tests
    private UUID testPersonId;

    private String testUsername;

    private String testUsername2;

    private UUID testPersonId2;

    @BeforeEach
    void setUp() {
        service = new UserPersonLinkServiceImpl(
                linkRepository, personRepository, workPhoneService, emailService, usernameService, eventPublisher);

        // Initialize fixed test values
        testPersonId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        testUsername = "marcus.webb";
        testUsername2 = "dana.cole";
        testPersonId2 = UUID.fromString("00000000-0000-0000-0000-000000000004");
    }

    @Test
    void linkUserToPerson_success() {
        LinkUserToPersonRequest request = new LinkUserToPersonRequest(testUsername, testPersonId);
        request.setLinkType("PRIMARY");
        request.setNotes("notes");

        Person person = Person.builder()
                .id(testPersonId)
                .firstName("Alex")
                .lastName("Smith")
                .build();
        when(personRepository.findById(testPersonId)).thenReturn(Optional.of(person));
        when(linkRepository.existsByUsername(testUsername)).thenReturn(false);
        when(linkRepository.save(any(UserPersonLink.class))).thenAnswer(invocation -> {
            UserPersonLink link = invocation.getArgument(0);
            link.setId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
            link.setCreatedAt(Instant.now(TEST_CLOCK));
            return link;
        });

        try (MockedStatic<SecurityContextHelper> helperMock = Mockito.mockStatic(SecurityContextHelper.class)) {
            helperMock
                    .when(() -> SecurityContextHelper.getCurrentUsernameOrDefault("system"))
                    .thenReturn("tester");

            UserPersonLinkResponse response = service.linkUserToPerson(request);

            assertThat(response).isNotNull();
            assertThat(response.getUsername()).isEqualTo(testUsername);
            assertThat(response.getPersonId()).isEqualTo(testPersonId);
            assertThat(response.getCreatedBy()).isEqualTo("tester");
            assertThat(response.getLinkId()).isNotNull();
        }
    }

    @Test
    void linkUserToPerson_personNotFound() {
        LinkUserToPersonRequest request = new LinkUserToPersonRequest(testUsername, testPersonId2);

        when(personRepository.findById(testPersonId2)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.linkUserToPerson(request))
                .isInstanceOf(PersonNotFoundException.class)
                .hasMessageContaining("Person not found");

        verify(linkRepository, never()).save(any(UserPersonLink.class));
    }

    @Test
    void linkUserToPerson_alreadyLinked() {
        LinkUserToPersonRequest request = new LinkUserToPersonRequest(testUsername, testPersonId);

        when(personRepository.findById(testPersonId))
                .thenReturn(Optional.of(Person.builder().id(testPersonId).build()));
        when(linkRepository.existsByUsername(testUsername)).thenReturn(true);

        assertThatThrownBy(() -> service.linkUserToPerson(request))
                .isInstanceOf(UserAlreadyLinkedException.class)
                .hasMessageContaining("already linked");

        verify(linkRepository, never()).save(any(UserPersonLink.class));
    }

    @Test
    void unlinkUserFromPerson_success() {
        UserPersonLink link = new UserPersonLink();
        link.setId(UUID.fromString("00000000-0000-0000-0000-0000000000aa"));
        link.setUsername(testUsername);
        link.setPerson(Person.builder().id(testPersonId).build());
        when(linkRepository.findByUsername(testUsername)).thenReturn(Optional.of(link));

        service.unlinkUserFromPerson(testUsername);

        verify(linkRepository).delete(link);
        verify(eventPublisher).publishLinkRemoved(link);
    }

    @Test
    void unlinkUserFromPerson_notFound() {
        when(linkRepository.findByUsername(testUsername2)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.unlinkUserFromPerson(testUsername2))
                .isInstanceOf(UserPersonLinkNotFoundException.class)
                .hasMessageContaining("No person link found");

        verify(linkRepository, never()).delete(any(UserPersonLink.class));
    }

    @Test
    void findPersonByUsername_success() {
        UserPersonLink link = new UserPersonLink();
        link.setUsername(testUsername);
        link.setPerson(Person.builder().id(testPersonId).build());

        Person person = Person.builder()
                .id(testPersonId)
                .firstName("Jordan")
                .lastName("Case")
                .build();

        when(linkRepository.findByUsername(testUsername)).thenReturn(Optional.of(link));
        when(personRepository.findById(testPersonId)).thenReturn(Optional.of(person));
        when(workPhoneService.getWorkPhones(testPersonId)).thenReturn(java.util.List.of());
        when(emailService.getEmails(testPersonId))
                .thenReturn(new com.positivity.peoplecontact.internal.service.PersonEmailService.EmailPair(
                        "jordan@example.com", null));

        PersonResponse response = service.findPersonByUsername(testUsername);

        assertThat(response).isNotNull();
        assertThat(response.getId()).isEqualTo(testPersonId);
        assertThat(response.getFirstName()).isEqualTo("Jordan");
        assertThat(response.getPrimaryEmail()).isEqualTo("jordan@example.com");
    }

    @Test
    void findPersonByUsername_notFound() {
        when(linkRepository.findByUsername(testUsername2)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findPersonByUsername(testUsername2))
                .isInstanceOf(UserPersonLinkNotFoundException.class)
                .hasMessageContaining("No person link found");
    }

    @Test
    void findUsernamesByPersonId_success() {
        when(personRepository.findById(testPersonId))
                .thenReturn(Optional.of(Person.builder().id(testPersonId).build()));

        UserPersonLink firstLink = new UserPersonLink();
        firstLink.setUsername(testUsername);
        firstLink.setPerson(Person.builder().id(testPersonId).build());

        UserPersonLink secondLink = new UserPersonLink();
        secondLink.setUsername(testUsername2);
        secondLink.setPerson(Person.builder().id(testPersonId).build());

        when(linkRepository.findByPerson_Id(testPersonId)).thenReturn(List.of(firstLink, secondLink));

        List<String> usernames = service.findUsernamesByPersonId(testPersonId);

        assertThat(usernames).containsExactly(testUsername, testUsername2);
    }

    @Test
    void findUsernamesByPersonId_empty() {
        when(personRepository.findById(testPersonId2))
                .thenReturn(Optional.of(Person.builder().id(testPersonId2).build()));
        when(linkRepository.findByPerson_Id(testPersonId2)).thenReturn(List.of());

        List<String> usernames = service.findUsernamesByPersonId(testPersonId2);

        assertThat(usernames).isEmpty();
    }
}
