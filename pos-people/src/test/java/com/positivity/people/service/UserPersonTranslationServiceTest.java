package com.positivity.people.service;

import com.positivity.people.internal.entity.UserPersonLink;
import com.positivity.people.internal.exception.NotFoundException;
import com.positivity.people.internal.repository.UserPersonLinkRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
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
        userPersonTranslationService = new UserPersonTranslationService(userPersonLinkRepository);
    }

    @Test
    void getUserIdByPersonId_returnsMappedUserId() {
        UUID personId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UserPersonLink link = new UserPersonLink();
        link.setPersonId(personId);
        link.setUserId(userId.toString());
        when(userPersonLinkRepository.findByPersonId(personId)).thenReturn(List.of(link));

        UUID result = userPersonTranslationService.getUserIdByPersonId(personId);

        assertEquals(userId, result);
    }

    @Test
    void getUserIdByPersonId_throwsWhenNoLinkExists() {
        UUID personId = UUID.randomUUID();
        when(userPersonLinkRepository.findByPersonId(personId)).thenReturn(List.of());

        assertThrows(NotFoundException.class, () -> userPersonTranslationService.getUserIdByPersonId(personId));
    }

    @Test
    void getUserIdByPersonId_throwsWhenLinkedUserIdIsNotUuid() {
        UUID personId = UUID.randomUUID();
        UserPersonLink link = new UserPersonLink();
        link.setPersonId(personId);
        link.setUserId("legacy-user-id");
        when(userPersonLinkRepository.findByPersonId(personId)).thenReturn(List.of(link));

        assertThrows(NotFoundException.class, () -> userPersonTranslationService.getUserIdByPersonId(personId));
    }

    @Test
    void getPersonIdByUserId_returnsMappedPersonId() {
        UUID userId = UUID.randomUUID();
        UUID personId = UUID.randomUUID();
        UserPersonLink link = new UserPersonLink();
        link.setPersonId(personId);
        link.setUserId(userId.toString());
        when(userPersonLinkRepository.findByUserId(userId.toString())).thenReturn(Optional.of(link));

        UUID result = userPersonTranslationService.getPersonIdByUserId(userId);

        assertEquals(personId, result);
    }

    @Test
    void getPersonIdByUserId_throwsWhenNoLinkExists() {
        UUID userId = UUID.randomUUID();
        when(userPersonLinkRepository.findByUserId(userId.toString())).thenReturn(Optional.empty());

        assertThrows(NotFoundException.class, () -> userPersonTranslationService.getPersonIdByUserId(userId));
    }
}