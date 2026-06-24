package com.positivity.people.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.positivity.people.internal.client.SecurityServiceClient;
import com.positivity.people.internal.client.dto.User;
import com.positivity.people.internal.entity.Person;
import com.positivity.people.internal.entity.UserPersonLink;
import com.positivity.people.internal.repository.UserPersonLinkRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PersonUsernameServiceTest {

    private static final UUID PERSON = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-000000000099");

    @Mock
    private UserPersonLinkRepository linkRepository;

    @Mock
    private SecurityServiceClient securityServiceClient;

    @InjectMocks
    private PersonUsernameService service;

    private UserPersonLink link() {
        UserPersonLink l = new UserPersonLink();
        l.setUserId(USER);
        l.setPerson(Person.builder().id(PERSON).build());
        return l;
    }

    private User user() {
        User u = new User();
        u.setId(USER);
        u.setUsername("jordan");
        return u;
    }

    @Test
    void usernameForPerson_resolvesViaLinkAndSecurity() {
        when(linkRepository.findByPerson_Id(PERSON)).thenReturn(List.of(link()));
        when(securityServiceClient.getUserById(USER)).thenReturn(Optional.of(user()));

        assertThat(service.usernameForPerson(PERSON)).isEqualTo("jordan");
    }

    @Test
    void usernameForPerson_nullWhenUnlinked() {
        when(linkRepository.findByPerson_Id(PERSON)).thenReturn(List.of());

        assertThat(service.usernameForPerson(PERSON)).isNull();
    }

    @Test
    void usernamesByPersonId_batchResolves() {
        when(linkRepository.findByPerson_IdIn(List.of(PERSON))).thenReturn(List.of(link()));
        when(securityServiceClient.getUsernamesByIds(org.mockito.ArgumentMatchers.any()))
                .thenReturn(Map.of(USER, "jordan"));

        Map<UUID, String> result = service.usernamesByPersonId(List.of(PERSON));

        assertThat(result).containsEntry(PERSON, "jordan");
    }
}
