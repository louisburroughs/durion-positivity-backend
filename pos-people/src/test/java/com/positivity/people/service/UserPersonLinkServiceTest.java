package com.positivity.people.service;

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
public class UserPersonLinkServiceTest {

    @Mock
    private UserPersonLinkRepository linkRepository;

    @Mock
    private PersonRepository personRepository;

    private UserPersonLinkServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new UserPersonLinkServiceImpl(linkRepository, personRepository);
    }

    @Test
    void linkUserToPerson_success() {
        UUID personId = UUID.randomUUID();
        LinkUserToPersonRequest request = new LinkUserToPersonRequest("user-1", personId);
        request.setLinkType("PRIMARY");
        request.setNotes("notes");

        Person person = Person.builder().id(personId).firstName("Alex").lastName("Smith").build();
        when(personRepository.findById(personId)).thenReturn(Optional.of(person));
        when(linkRepository.existsByUserId("user-1")).thenReturn(false);
        when(linkRepository.save(any(UserPersonLink.class))).thenAnswer(invocation -> {
            UserPersonLink link = invocation.getArgument(0);
            link.setId(UUID.randomUUID());
            link.setCreatedAt(Instant.now());
            return link;
        });

        try (MockedStatic<SecurityContextHelper> helperMock = Mockito.mockStatic(SecurityContextHelper.class)) {
            helperMock.when(() -> SecurityContextHelper.getCurrentUsernameOrDefault("system"))
                    .thenReturn("tester");

            UserPersonLinkResponse response = service.linkUserToPerson(request);

            assertThat(response).isNotNull();
            assertThat(response.getUserId()).isEqualTo("user-1");
            assertThat(response.getPersonId()).isEqualTo(personId);
            assertThat(response.getCreatedBy()).isEqualTo("tester");
            assertThat(response.getLinkId()).isNotNull();
        }
    }

    @Test
    void linkUserToPerson_personNotFound() {
        UUID personId = UUID.randomUUID();
        LinkUserToPersonRequest request = new LinkUserToPersonRequest("user-404", personId);

        when(personRepository.findById(personId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.linkUserToPerson(request))
                .isInstanceOf(PersonNotFoundException.class)
                .hasMessageContaining("Person not found");

        verify(linkRepository, never()).save(any(UserPersonLink.class));
    }

    @Test
    void linkUserToPerson_alreadyLinked() {
        UUID personId = UUID.randomUUID();
        LinkUserToPersonRequest request = new LinkUserToPersonRequest("user-dup", personId);

        when(personRepository.findById(personId)).thenReturn(Optional.of(Person.builder().id(personId).build()));
        when(linkRepository.existsByUserId("user-dup")).thenReturn(true);

        assertThatThrownBy(() -> service.linkUserToPerson(request))
                .isInstanceOf(UserAlreadyLinkedException.class)
                .hasMessageContaining("already linked");

        verify(linkRepository, never()).save(any(UserPersonLink.class));
    }

    @Test
    void unlinkUserFromPerson_success() {
        when(linkRepository.existsByUserId("user-unlink")).thenReturn(true);

        service.unlinkUserFromPerson("user-unlink");

        verify(linkRepository).deleteByUserId("user-unlink");
    }

    @Test
    void unlinkUserFromPerson_notFound() {
        when(linkRepository.existsByUserId("missing-user")).thenReturn(false);

        assertThatThrownBy(() -> service.unlinkUserFromPerson("missing-user"))
                .isInstanceOf(UserPersonLinkNotFoundException.class)
                .hasMessageContaining("No person link found");

        verify(linkRepository, never()).deleteByUserId(any());
    }

    @Test
    void findPersonByUserId_success() {
        UUID personId = UUID.randomUUID();
        UserPersonLink link = new UserPersonLink();
        link.setUserId("user-find");
        link.setPersonId(personId);

        Person person = Person.builder()
                .id(personId)
                .firstName("Jordan")
                .lastName("Case")
                .primaryEmail("jordan@example.com")
                .build();

        when(linkRepository.findByUserId("user-find")).thenReturn(Optional.of(link));
        when(personRepository.findById(personId)).thenReturn(Optional.of(person));

        PersonResponse response = service.findPersonByUserId("user-find");

        assertThat(response).isNotNull();
        assertThat(response.getId()).isEqualTo(personId);
        assertThat(response.getFirstName()).isEqualTo("Jordan");
        assertThat(response.getPrimaryEmail()).isEqualTo("jordan@example.com");
    }

    @Test
    void findPersonByUserId_notFound() {
        when(linkRepository.findByUserId("unknown-user")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findPersonByUserId("unknown-user"))
                .isInstanceOf(UserPersonLinkNotFoundException.class)
                .hasMessageContaining("No person link found");
    }

    @Test
    void findUserIdsByPersonId_success() {
        UUID personId = UUID.randomUUID();
        when(personRepository.findById(personId)).thenReturn(Optional.of(Person.builder().id(personId).build()));

        UserPersonLink firstLink = new UserPersonLink();
        firstLink.setUserId("user-a");
        firstLink.setPersonId(personId);

        UserPersonLink secondLink = new UserPersonLink();
        secondLink.setUserId("user-b");
        secondLink.setPersonId(personId);

        when(linkRepository.findByPersonId(personId)).thenReturn(List.of(firstLink, secondLink));

        List<String> userIds = service.findUserIdsByPersonId(personId);

        assertThat(userIds).containsExactly("user-a", "user-b");
    }

    @Test
    void findUserIdsByPersonId_empty() {
        UUID personId = UUID.randomUUID();
        when(personRepository.findById(personId)).thenReturn(Optional.of(Person.builder().id(personId).build()));
        when(linkRepository.findByPersonId(personId)).thenReturn(List.of());

        List<String> userIds = service.findUserIdsByPersonId(personId);

        assertThat(userIds).isEmpty();
    }
}