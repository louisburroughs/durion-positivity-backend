package com.positivity.people.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.positivity.people.internal.entity.ExtUserLinkReplica;
import com.positivity.people.internal.repository.ExtUserLinkReplicaRepository;
import com.positivity.people.internal.service.UserPersonTranslationServiceImpl;
import jakarta.persistence.EntityNotFoundException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class UserPersonTranslationServiceTest {

    private ExtUserLinkReplicaRepository linkReplicaRepository;

    private UserPersonTranslationService userPersonTranslationService;

    private UUID testPersonId;

    private UUID missingPersonId;

    private String testUsername;

    private String missingUsername;

    @BeforeEach
    void setUp() {
        linkReplicaRepository = mock(ExtUserLinkReplicaRepository.class);
        userPersonTranslationService = new UserPersonTranslationServiceImpl(linkReplicaRepository);
        testPersonId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        missingPersonId = UUID.fromString("00000000-0000-0000-0000-000000000002");
        testUsername = "jordan";
        missingUsername = "nobody";
    }

    private ExtUserLinkReplica link() {
        return ExtUserLinkReplica.builder()
                .linkId(UUID.fromString("00000000-0000-0000-0000-0000000000aa"))
                .personId(testPersonId)
                .username(testUsername)
                .status("ACTIVE")
                .aggregateVersion(0)
                .updatedAt(Instant.now())
                .build();
    }

    @Test
    void getPersonUuidForUser_returnsMappedPersonUuid() {
        when(linkReplicaRepository.findFirstByUsername(testUsername)).thenReturn(Optional.of(link()));

        UUID result = userPersonTranslationService.getPersonUuidForUser(testUsername);

        assertEquals(testPersonId, result);
    }

    @Test
    void getPersonUuidForUser_throwsWhenNoLinkExists() {
        when(linkReplicaRepository.findFirstByUsername(missingUsername)).thenReturn(Optional.empty());

        assertThrows(
                EntityNotFoundException.class,
                () -> userPersonTranslationService.getPersonUuidForUser(missingUsername));
    }

    @Test
    void getUsernameForPerson_returnsOptionalUsername() {
        when(linkReplicaRepository.findByPersonIdAndStatus(testPersonId, "ACTIVE"))
                .thenReturn(List.of(link()));

        Optional<String> result = userPersonTranslationService.getUsernameForPerson(testPersonId);

        assertEquals(Optional.of(testUsername), result);
    }

    @Test
    void getUsernameForPerson_returnsEmptyWhenNoLinkExists() {
        when(linkReplicaRepository.findByPersonIdAndStatus(missingPersonId, "ACTIVE"))
                .thenReturn(List.of());

        Optional<String> result = userPersonTranslationService.getUsernameForPerson(missingPersonId);

        assertEquals(Optional.empty(), result);
    }

    @Test
    void isUserLinkedToPerson_returnsTrueWhenLinkExists() {
        when(linkReplicaRepository.existsByUsernameAndPersonId(testUsername, testPersonId))
                .thenReturn(true);

        boolean result = userPersonTranslationService.isUserLinkedToPerson(testUsername, testPersonId);

        assertEquals(true, result);
    }
}
