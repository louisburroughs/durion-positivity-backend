package com.positivity.people.internal.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.positivity.people.internal.entity.ExtUserLinkReplica;
import com.positivity.people.internal.repository.ExtUserLinkReplicaRepository;
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

    @Test
    void findActivePersonUuidForUser_returnsPersonWhenTheLinkIsActive() {
        when(linkReplicaRepository.findFirstByUsernameAndStatus(testUsername, "ACTIVE"))
                .thenReturn(Optional.of(link()));

        assertEquals(Optional.of(testPersonId), userPersonTranslationService.findActivePersonUuidForUser(testUsername));
    }

    @Test
    void findActivePersonUuidForUser_isEmptyWhenOnlyAnInactiveLinkExists() {
        // The repository query filters on ACTIVE, so a revoked link simply does not come back.
        // WorkSessionAccessPolicy reads "empty" as "not this person" and falls through to the
        // supervisory check rather than granting the self path (#2062 review).
        when(linkReplicaRepository.findFirstByUsernameAndStatus(testUsername, "ACTIVE"))
                .thenReturn(Optional.empty());

        assertEquals(Optional.empty(), userPersonTranslationService.findActivePersonUuidForUser(testUsername));
    }

    @Test
    void findActivePersonUuidForUser_neverThrowsForAnUnknownUser() {
        when(linkReplicaRepository.findFirstByUsernameAndStatus(missingUsername, "ACTIVE"))
                .thenReturn(Optional.empty());

        // Deliberately not an exception: it is read inside a caller's transaction.
        assertEquals(Optional.empty(), userPersonTranslationService.findActivePersonUuidForUser(missingUsername));
    }
}
