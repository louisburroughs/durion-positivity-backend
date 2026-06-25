package com.positivity.people.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.positivity.people.internal.entity.Person;
import com.positivity.people.internal.entity.UserPersonLink;
import com.positivity.people.internal.enums.UserLinkStatus;
import com.positivity.people.internal.repository.UserPersonLinkRepository;
import com.positivity.people.internal.service.UserPersonTranslationServiceImpl;
import jakarta.persistence.EntityNotFoundException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class UserPersonTranslationServiceTest {

    private UserPersonLinkRepository userPersonLinkRepository;

    private UserPersonTranslationService userPersonTranslationService;

    private UUID testPersonId;

    private UUID missingPersonId;

    private String testUsername;

    private String missingUsername;

    @BeforeEach
    void setUp() {
        userPersonLinkRepository = mock(UserPersonLinkRepository.class);
        userPersonTranslationService = new UserPersonTranslationServiceImpl(userPersonLinkRepository);
        testPersonId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        missingPersonId = UUID.fromString("00000000-0000-0000-0000-000000000002");
        testUsername = "jordan";
        missingUsername = "nobody";
    }

    @Test
    void getPersonUuidForUser_returnsMappedPersonUuid() {
        UserPersonLink link = new UserPersonLink();
        link.setPerson(Person.builder().id(testPersonId).build());
        link.setUsername(testUsername);
        when(userPersonLinkRepository.findByUsername(testUsername)).thenReturn(Optional.of(link));

        UUID result = userPersonTranslationService.getPersonUuidForUser(testUsername);

        assertEquals(testPersonId, result);
    }

    @Test
    void getPersonUuidForUser_throwsWhenNoLinkExists() {
        when(userPersonLinkRepository.findByUsername(missingUsername)).thenReturn(Optional.empty());

        assertThrows(
                EntityNotFoundException.class,
                () -> userPersonTranslationService.getPersonUuidForUser(missingUsername));
    }

    @Test
    void getUsernameForPerson_returnsOptionalUsername() {
        UserPersonLink link = new UserPersonLink();
        link.setPerson(Person.builder().id(testPersonId).build());
        link.setUsername(testUsername);
        link.setStatus(UserLinkStatus.ACTIVE);
        when(userPersonLinkRepository.findByPerson_IdAndStatus(testPersonId, UserLinkStatus.ACTIVE))
                .thenReturn(Optional.of(link));

        Optional<String> result = userPersonTranslationService.getUsernameForPerson(testPersonId);

        assertEquals(Optional.of(testUsername), result);
    }

    @Test
    void getUsernameForPerson_returnsEmptyWhenNoLinkExists() {
        when(userPersonLinkRepository.findByPerson_IdAndStatus(missingPersonId, UserLinkStatus.ACTIVE))
                .thenReturn(Optional.empty());

        Optional<String> result = userPersonTranslationService.getUsernameForPerson(missingPersonId);

        assertEquals(Optional.empty(), result);
    }

    @Test
    void isUserLinkedToPerson_returnsTrueWhenLinkExists() {
        when(userPersonLinkRepository.existsByUsernameAndPerson_Id(testUsername, testPersonId))
                .thenReturn(true);

        boolean result = userPersonTranslationService.isUserLinkedToPerson(testUsername, testPersonId);

        assertEquals(true, result);
    }
}
