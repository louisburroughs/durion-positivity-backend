package com.positivity.people.internal.service;

import com.positivity.people.internal.entity.ExtUserLinkReplica;
import com.positivity.people.internal.repository.ExtUserLinkReplicaRepository;
import com.positivity.people.service.UserPersonTranslationService;
import com.positivity.security.common.SecurityContextHelper;
import jakarta.persistence.EntityNotFoundException;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Username ↔ person translation backed by the {@code ext_people_contact_user_link} replica
 * (ADR-0044 §6, #875): pos-people-contact owns the links; this module reads its event-fed copy.
 */
@Service
@Transactional(readOnly = true)
public class UserPersonTranslationServiceImpl implements UserPersonTranslationService {

    private static final String ACTIVE = "ACTIVE";

    private final ExtUserLinkReplicaRepository linkReplicaRepository;

    public UserPersonTranslationServiceImpl(@NonNull ExtUserLinkReplicaRepository linkReplicaRepository) {
        this.linkReplicaRepository = linkReplicaRepository;
    }

    @Override
    @NonNull
    public UUID getPersonUuidForUser(@NonNull String username) {
        return linkReplicaRepository
                .findFirstByUsername(username)
                .map(ExtUserLinkReplica::getPersonId)
                .orElseThrow(() -> new EntityNotFoundException("No person link found for username: " + username));
    }

    @Override
    @NonNull
    public UUID getPersonUuidForCurrentUser() {
        String username;
        try {
            // Throws IllegalStateException (never returns empty) when the security
            // context is missing or carries no username.
            username = SecurityContextHelper.getCurrentUsername().orElse(null);
        } catch (IllegalStateException ex) {
            username = null;
        }
        if (username == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authenticated user context is missing");
        }
        return getPersonUuidForUser(username);
    }

    @Override
    @NonNull
    public Optional<String> getUsernameForPerson(@NonNull UUID personUuid) {
        return linkReplicaRepository.findByPersonIdAndStatus(personUuid, ACTIVE).stream()
                .map(ExtUserLinkReplica::getUsername)
                .findFirst();
    }

    @Override
    public boolean isUserLinkedToPerson(@NonNull String username, @NonNull UUID personUuid) {
        return linkReplicaRepository.existsByUsernameAndPersonId(username, personUuid);
    }
}
