package com.positivity.people.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.people.internal.dto.InactivePersonActiveUserResponse;
import com.positivity.people.internal.dto.LinkUserToPersonRequest;
import com.positivity.people.internal.dto.PersonResponse;
import com.positivity.people.internal.dto.UserPersonLinkResponse;
import com.positivity.people.internal.entity.Employee;
import com.positivity.people.internal.entity.Person;
import com.positivity.people.internal.entity.UserPersonLink;
import com.positivity.people.internal.enums.EmployeeStatus;
import com.positivity.people.internal.enums.UserLinkStatus;
import com.positivity.people.internal.exception.PersonNotFoundException;
import com.positivity.people.internal.exception.UserAlreadyLinkedException;
import com.positivity.people.internal.exception.UserPersonLinkNotFoundException;
import com.positivity.people.internal.repository.EmployeeRepository;
import com.positivity.people.internal.repository.PersonRepository;
import com.positivity.people.internal.repository.UserPersonLinkRepository;
import com.positivity.people.internal.service.UserPersonLinkServiceImpl;
import com.positivity.security.common.SecurityContextHelper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
    private com.positivity.people.internal.service.PersonWorkPhoneService workPhoneService;

    @Mock
    private com.positivity.people.internal.service.PersonEmailService emailService;

    @Mock
    private com.positivity.people.internal.service.PersonUsernameService usernameService;

    @Mock
    private EmployeeRepository employeeRepository;

    private UserPersonLinkServiceImpl service;

    // Fixed UUIDs for deterministic tests
    private UUID testPersonId;

    private UUID testUserId;

    private UUID testUserId2;

    private UUID testPersonId2;

    @BeforeEach
    void setUp() {
        service = new UserPersonLinkServiceImpl(
                linkRepository, personRepository, workPhoneService, emailService, usernameService, employeeRepository);

        // Initialize fixed test UUIDs
        testPersonId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        testUserId = UUID.fromString("00000000-0000-0000-0000-000000000002");
        testUserId2 = UUID.fromString("00000000-0000-0000-0000-000000000003");
        testPersonId2 = UUID.fromString("00000000-0000-0000-0000-000000000004");
    }

    @Test
    void linkUserToPerson_success() {
        LinkUserToPersonRequest request = new LinkUserToPersonRequest(testUserId, testPersonId);
        request.setLinkType("PRIMARY");
        request.setNotes("notes");

        Person person = Person.builder()
                .id(testPersonId)
                .firstName("Alex")
                .lastName("Smith")
                .build();
        when(personRepository.findById(testPersonId)).thenReturn(Optional.of(person));
        when(linkRepository.existsByUserId(testUserId)).thenReturn(false);
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
            assertThat(response.getUserId()).isEqualTo(testUserId);
            assertThat(response.getPersonId()).isEqualTo(testPersonId);
            assertThat(response.getCreatedBy()).isEqualTo("tester");
            assertThat(response.getLinkId()).isNotNull();
        }
    }

    @Test
    void linkUserToPerson_personNotFound() {
        LinkUserToPersonRequest request = new LinkUserToPersonRequest(testUserId, testPersonId2);

        when(personRepository.findById(testPersonId2)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.linkUserToPerson(request))
                .isInstanceOf(PersonNotFoundException.class)
                .hasMessageContaining("Person not found");

        verify(linkRepository, never()).save(any(UserPersonLink.class));
    }

    @Test
    void linkUserToPerson_alreadyLinked() {
        LinkUserToPersonRequest request = new LinkUserToPersonRequest(testUserId, testPersonId);

        when(personRepository.findById(testPersonId))
                .thenReturn(Optional.of(Person.builder().id(testPersonId).build()));
        when(linkRepository.existsByUserId(testUserId)).thenReturn(true);

        assertThatThrownBy(() -> service.linkUserToPerson(request))
                .isInstanceOf(UserAlreadyLinkedException.class)
                .hasMessageContaining("already linked");

        verify(linkRepository, never()).save(any(UserPersonLink.class));
    }

    @Test
    void unlinkUserFromPerson_success() {
        when(linkRepository.existsByUserId(testUserId)).thenReturn(true);

        service.unlinkUserFromPerson(testUserId);

        verify(linkRepository).deleteByUserId(testUserId);
    }

    @Test
    void unlinkUserFromPerson_notFound() {
        when(linkRepository.existsByUserId(testUserId2)).thenReturn(false);

        assertThatThrownBy(() -> service.unlinkUserFromPerson(testUserId2))
                .isInstanceOf(UserPersonLinkNotFoundException.class)
                .hasMessageContaining("No person link found");

        verify(linkRepository, never()).deleteByUserId(any());
    }

    @Test
    void findPersonByUserId_success() {
        UserPersonLink link = new UserPersonLink();
        link.setUserId(testUserId);
        link.setPerson(Person.builder().id(testPersonId).build());

        Person person = Person.builder()
                .id(testPersonId)
                .firstName("Jordan")
                .lastName("Case")
                .build();

        when(linkRepository.findByUserId(testUserId)).thenReturn(Optional.of(link));
        when(personRepository.findById(testPersonId)).thenReturn(Optional.of(person));
        when(workPhoneService.getWorkPhones(testPersonId)).thenReturn(java.util.List.of());
        when(emailService.getEmails(testPersonId))
                .thenReturn(new com.positivity.people.internal.service.PersonEmailService.EmailPair(
                        "jordan@example.com", null));

        PersonResponse response = service.findPersonByUserId(testUserId);

        assertThat(response).isNotNull();
        assertThat(response.getId()).isEqualTo(testPersonId);
        assertThat(response.getFirstName()).isEqualTo("Jordan");
        assertThat(response.getPrimaryEmail()).isEqualTo("jordan@example.com");
    }

    @Test
    void findPersonByUserId_notFound() {
        when(linkRepository.findByUserId(testUserId2)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findPersonByUserId(testUserId2))
                .isInstanceOf(UserPersonLinkNotFoundException.class)
                .hasMessageContaining("No person link found");
    }

    @Test
    void findUserIdsByPersonId_success() {
        when(personRepository.findById(testPersonId))
                .thenReturn(Optional.of(Person.builder().id(testPersonId).build()));

        UserPersonLink firstLink = new UserPersonLink();
        firstLink.setUserId(testUserId);
        firstLink.setPerson(Person.builder().id(testPersonId).build());

        UserPersonLink secondLink = new UserPersonLink();
        secondLink.setUserId(testUserId2);
        secondLink.setPerson(Person.builder().id(testPersonId).build());

        when(linkRepository.findByPerson_Id(testPersonId)).thenReturn(List.of(firstLink, secondLink));

        List<UUID> userIds = service.findUserIdsByPersonId(testPersonId);

        assertThat(userIds).containsExactly(testUserId, testUserId2);
    }

    @Test
    void findUserIdsByPersonId_empty() {
        when(personRepository.findById(testPersonId2))
                .thenReturn(Optional.of(Person.builder().id(testPersonId2).build()));
        when(linkRepository.findByPerson_Id(testPersonId2)).thenReturn(List.of());

        List<UUID> userIds = service.findUserIdsByPersonId(testPersonId2);

        assertThat(userIds).isEmpty();
    }

    @Test
    void findActiveUsersForInactivePersons_mapsViolations() {
        Person terminated = Person.builder()
                .id(testPersonId)
                .firstName("Tina")
                .lastName("Gone")
                .build();
        UserPersonLink link = new UserPersonLink();
        link.setId(UUID.fromString("00000000-0000-0000-0000-0000000000aa"));
        link.setUserId(testUserId);
        link.setPerson(terminated);
        link.setStatus(UserLinkStatus.ACTIVE);

        Employee terminatedEmployee = Employee.builder()
                .personId(testPersonId)
                .status(EmployeeStatus.TERMINATED)
                .statusEffectiveAt(Instant.parse("2026-01-15T09:30:00Z"))
                .build();

        when(linkRepository.findActiveLinksForInactiveEmployees(eq(UserLinkStatus.ACTIVE), anyCollection()))
                .thenReturn(List.of(link));
        when(employeeRepository.findByPersonIdIn(anyCollection())).thenReturn(List.of(terminatedEmployee));

        List<InactivePersonActiveUserResponse> result = service.findActiveUsersForInactivePersons();

        assertThat(result).hasSize(1);
        InactivePersonActiveUserResponse row = result.get(0);
        assertThat(row.getUserId()).isEqualTo(testUserId);
        assertThat(row.getPersonId()).isEqualTo(testPersonId);
        assertThat(row.getPersonStatus()).isEqualTo(EmployeeStatus.TERMINATED);
        assertThat(row.getPersonStatusEffectiveAt()).isEqualTo(Instant.parse("2026-01-15T09:30:00Z"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void findActiveUsersForInactivePersons_queriesActiveLinksAndInactiveStatuses() {
        when(linkRepository.findActiveLinksForInactiveEmployees(eq(UserLinkStatus.ACTIVE), anyCollection()))
                .thenReturn(List.of());

        assertThat(service.findActiveUsersForInactivePersons()).isEmpty();

        ArgumentCaptor<Collection<EmployeeStatus>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(linkRepository).findActiveLinksForInactiveEmployees(eq(UserLinkStatus.ACTIVE), captor.capture());
        assertThat(captor.getValue())
                .containsExactlyInAnyOrder(
                        EmployeeStatus.SUSPENDED, EmployeeStatus.TERMINATED, EmployeeStatus.DISABLED);
    }
}
