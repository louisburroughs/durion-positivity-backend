package com.positivity.peoplecontact.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.positivity.peoplecontact.internal.entity.Person;
import com.positivity.peoplecontact.internal.entity.UserPersonLink;
import com.positivity.peoplecontact.internal.repository.UserPersonLinkRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PersonUsernameServiceTest {

    private static final UUID PERSON = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Mock
    private UserPersonLinkRepository linkRepository;

    @InjectMocks
    private PersonUsernameService service;

    private UserPersonLink link() {
        UserPersonLink l = new UserPersonLink();
        l.setUsername("jordan");
        l.setPerson(Person.builder().id(PERSON).build());
        return l;
    }

    @Test
    void usernameForPerson_resolvesViaLink() {
        when(linkRepository.findByPerson_Id(PERSON)).thenReturn(List.of(link()));

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

        Map<UUID, String> result = service.usernamesByPersonId(List.of(PERSON));

        assertThat(result).containsEntry(PERSON, "jordan");
    }
}
