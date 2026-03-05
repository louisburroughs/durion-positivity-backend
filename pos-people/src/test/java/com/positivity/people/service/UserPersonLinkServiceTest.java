package com.positivity.people.service;

import java.time.ZoneOffset;
import java.time.Clock;

import com.positivity.people.internal.dto.LinkUserToPersonRequest;
import com.positivity.people.internal.dto.PersonResponse;
import com.positivity.people.internal.dto.UserPersonLinkResponse;
import com.positivity.people.internal.entity.Person;
import com.positivity.people.internal.entity.UserPersonLink;
import com.positivity.people.internal.exception.PersonNotFoundException;
import com.positivity.people.internal.exception.UserAlreadyLinkedException;
import com.positivity.people.internal.exception.UserPersonLinkNotFoundException;
import com.positivity.people.internal.repository.PersonRepository;
import com.positivity.people.internal.repository.UserPersonLinkRepository;
import com.positivity.people.internal.service.UserPersonLinkServiceImpl;
import com.positivity.security.common.SecurityContextHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserPersonLinkServiceTest {
    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);


    @Mock
    private UserPersonLinkRepository linkRepository;

    @Mock
    private PersonRepository personRepository;

    private UserPersonLinkServiceImpl service;

    // Fixed UUIDs for deterministic tests
    private UUID testPersonId;
    private UUID testUserId;
    private UUID testUserId2;
    private UUID testPersonId2;

    @BeforeEach
    void setUp() {
        service = new UserPersonLinkServiceImpl(linkRepository, personRepository);

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

        Person person = Person.builder().id(testPersonId).firstName("Alex").lastName("Smith").build();
        when(personRepository.findById(testPersonId)).thenReturn(Optional.of(person));
        when(linkRepository.existsByUserId(testUserId)).thenReturn(false);
        when(linkRepository.save(any(UserPersonLink.class))).thenAnswer(invocation -> {
            UserPersonLink link = invocation.getArgument(0);
            link.setId(UUID.randomUUID());
            link.setCreatedAt(Instant.now(TEST_CLOCK));
            return link;
        });

        try (MockedStatic<SecurityContextHelper> helperMock = Mockito.mockStatic(SecurityContextHelper.class)) {
            helperMock.when(() -> SecurityContextHelper.getCurrentUsernameOrDefault("system"))
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
        link.setPersonId(testPersonId);

        Person person = Person.builder()
                .id(testPersonId)
                .firstName("Jordan")
                .lastName("Case")
                .primaryEmail("jordan@example.com")
                .build();

        when(linkRepository.findByUserId(testUserId)).thenReturn(Optional.of(link));
        when(personRepository.findById(testPersonId)).thenReturn(Optional.of(person));

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
        firstLink.setPersonId(testPersonId);

        UserPersonLink secondLink = new UserPersonLink();
        secondLink.setUserId(testUserId2);
        secondLink.setPersonId(testPersonId);

        when(linkRepository.findByPersonId(testPersonId)).thenReturn(List.of(firstLink, secondLink));

        List<UUID> userIds = service.findUserIdsByPersonId(testPersonId);

        assertThat(userIds).containsExactly(testUserId, testUserId2);
    }

    @Test
    void findUserIdsByPersonId_empty() {
        when(personRepository.findById(testPersonId2))
                .thenReturn(Optional.of(Person.builder().id(testPersonId2).build()));
        when(linkRepository.findByPersonId(testPersonId2)).thenReturn(List.of());

        List<UUID> userIds = service.findUserIdsByPersonId(testPersonId2);

        assertThat(userIds).isEmpty();
    }
}
