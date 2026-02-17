package com.positivity.people.service;

import com.positivity.people.internal.entity.UserPersonLink;
import com.positivity.people.internal.enums.UserLinkStatus;
import com.positivity.people.internal.repository.UserPersonLinkRepository;
import com.positivity.people.internal.service.UserPersonTranslationServiceImpl;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UserPersonTranslationServiceTest {

    private UserPersonLinkRepository userPersonLinkRepository;
    private UserPersonTranslationService userPersonTranslationService;

    @BeforeEach
    void setUp() {
        userPersonLinkRepository = mock(UserPersonLinkRepository.class);
        userPersonTranslationService = new UserPersonTranslationServiceImpl(userPersonLinkRepository);
    }

    @Test
    void getPersonUuidForUser_returnsMappedPersonUuid() {
        UUID personUuid = UUID.randomUUID();
        String userId = "user-123";
        UserPersonLink link = new UserPersonLink();
        link.setPersonId(personUuid);
        link.setUserId(userId);
        when(userPersonLinkRepository.findByUserId(userId)).thenReturn(Optional.of(link));

        UUID result = userPersonTranslationService.getPersonUuidForUser(userId);

        assertEquals(personUuid, result);
    }

    @Test
    void getPersonUuidForUser_throwsWhenNoLinkExists() {
        String userId = "missing-user";
        when(userPersonLinkRepository.findByUserId(userId)).thenReturn(Optional.empty());

        assertThrows(EntityNotFoundException.class, () -> userPersonTranslationService.getPersonUuidForUser(userId));
    }

    @Test
    void getUserIdForPerson_returnsOptionalUserId() {
        UUID personUuid = UUID.randomUUID();
        UserPersonLink link = new UserPersonLink();
        link.setPersonId(personUuid);
        link.setUserId("user-123");
        link.setStatus(UserLinkStatus.ACTIVE);
        when(userPersonLinkRepository.findByPersonIdAndStatus(personUuid, UserLinkStatus.ACTIVE))
                .thenReturn(Optional.of(link));

        Optional<String> result = userPersonTranslationService.getUserIdForPerson(personUuid);

        assertEquals(Optional.of("user-123"), result);
    }

    @Test
    void getUserIdForPerson_returnsEmptyWhenNoLinkExists() {
        UUID personUuid = UUID.randomUUID();
        when(userPersonLinkRepository.findByPersonIdAndStatus(personUuid, UserLinkStatus.ACTIVE))
                .thenReturn(Optional.empty());

        Optional<String> result = userPersonTranslationService.getUserIdForPerson(personUuid);

        assertEquals(Optional.empty(), result);
    }

    @Test
    void isUserLinkedToPerson_returnsTrueWhenLinkExists() {
        String userId = "user-123";
        UUID personUuid = UUID.randomUUID();
        when(userPersonLinkRepository.existsByUserIdAndPersonId(userId, personUuid)).thenReturn(true);

        boolean result = userPersonTranslationService.isUserLinkedToPerson(userId, personUuid);

        assertEquals(true, result);
    }
}