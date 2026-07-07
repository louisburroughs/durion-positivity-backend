package com.positivity.people.internal.service;

import com.positivity.people.internal.entity.UserPersonLink;
import com.positivity.people.internal.enums.UserLinkStatus;
import com.positivity.people.internal.repository.UserPersonLinkRepository;
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

@Service
@Transactional(readOnly = true)
public class UserPersonTranslationServiceImpl implements UserPersonTranslationService {

    private final UserPersonLinkRepository userPersonLinkRepository;

    public UserPersonTranslationServiceImpl(@NonNull UserPersonLinkRepository userPersonLinkRepository) {
        this.userPersonLinkRepository = userPersonLinkRepository;
    }

    @Override
    @NonNull
    public UUID getPersonUuidForUser(@NonNull String username) {
        return userPersonLinkRepository
                .findByUsername(username)
                .map(UserPersonLink::getPersonId)
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
        return userPersonLinkRepository
                .findByPerson_IdAndStatus(personUuid, UserLinkStatus.ACTIVE)
                .map(UserPersonLink::getUsername);
    }

    @Override
    public boolean isUserLinkedToPerson(@NonNull String username, @NonNull UUID personUuid) {
        return userPersonLinkRepository.existsByUsernameAndPerson_Id(username, personUuid);
    }
}
