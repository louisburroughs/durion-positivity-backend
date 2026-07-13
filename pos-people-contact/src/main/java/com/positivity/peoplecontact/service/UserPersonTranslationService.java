package com.positivity.peoplecontact.service;

import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

public interface UserPersonTranslationService {

    @NonNull
    UUID getPersonUuidForUser(@NonNull String username);

    /**
     * Resolve the current authenticated user's person id from the security context.
     * @return person UUID linked to the current user
     * @throws org.springframework.web.server.ResponseStatusException 401 when no
     * authenticated user context is present
     * @throws jakarta.persistence.EntityNotFoundException when the user has no person link
     */
    @NonNull
    UUID getPersonUuidForCurrentUser();

    @NonNull
    Optional<String> getUsernameForPerson(@NonNull UUID personUuid);

    boolean isUserLinkedToPerson(@NonNull String username, @NonNull UUID personUuid);
}
