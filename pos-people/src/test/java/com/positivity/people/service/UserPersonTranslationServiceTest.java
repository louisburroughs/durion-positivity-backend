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
    private UUID testPersonId;
    private UUID missingPersonId;
    private UUID testUserId;
    private UUID missingUserId;

    @BeforeEach
    void setUp() {
        userPersonLinkRepository = mock(UserPersonLinkRepository.class);
        userPersonTranslationService = new UserPersonTranslationServiceImpl(userPersonLinkRepository);
        testPersonId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        missingPersonId = UUID.fromString("00000000-0000-0000-0000-000000000002");
        testUserId = UUID.fromString("00000000-0000-0000-0000-000000000003");
        missingUserId = UUID.fromString("00000000-0000-0000-0000-000000000004");
    }

    @Test
    void getPersonUuidForUser_returnsMappedPersonUuid() {
        UserPersonLink link = new UserPersonLink();
        link.setPersonId(testPersonId);
        link.setUserId(testUserId);
        when(userPersonLinkRepository.findByUserId(testUserId)).thenReturn(Optional.of(link));

        UUID result = userPersonTranslationService.getPersonUuidForUser(testUserId);

        assertEquals(testPersonId, result);
    }

    @Test
    void getPersonUuidForUser_throwsWhenNoLinkExists() {
        when(userPersonLinkRepository.findByUserId(missingUserId)).thenReturn(Optional.empty());

        assertThrows(EntityNotFoundException.class,
                () -> userPersonTranslationService.getPersonUuidForUser(missingUserId));
    }

    @Test
    void getUserIdForPerson_returnsOptionalUserId() {
        UserPersonLink link = new UserPersonLink();
        link.setPersonId(testPersonId);
        link.setUserId(testUserId);
        link.setStatus(UserLinkStatus.ACTIVE);
        when(userPersonLinkRepository.findByPersonIdAndStatus(testPersonId, UserLinkStatus.ACTIVE))
                .thenReturn(Optional.of(link));

        Optional<UUID> result = userPersonTranslationService.getUserIdForPerson(testPersonId);

        assertEquals(Optional.of(testUserId), result);
    }

    @Test
    void getUserIdForPerson_returnsEmptyWhenNoLinkExists() {
        when(userPersonLinkRepository.findByPersonIdAndStatus(missingPersonId, UserLinkStatus.ACTIVE))
                .thenReturn(Optional.empty());

        Optional<UUID> result = userPersonTranslationService.getUserIdForPerson(missingPersonId);

        assertEquals(Optional.empty(), result);
    }

    @Test
    void isUserLinkedToPerson_returnsTrueWhenLinkExists() {
        when(userPersonLinkRepository.existsByUserIdAndPersonId(testUserId, testPersonId)).thenReturn(true);

        boolean result = userPersonTranslationService.isUserLinkedToPerson(testUserId, testPersonId);

        assertEquals(true, result);
    }
}