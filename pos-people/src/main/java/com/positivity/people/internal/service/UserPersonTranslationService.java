package com.positivity.people.internal.service;

import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

public interface UserPersonTranslationService {

    @NonNull
    UUID getPersonUuidForUser(@NonNull String username);

    /**
     * The person linked to a username, or empty when there is no link. Unlike
     * {@link #getPersonUuidForUser} this never throws, so a caller inside a transaction can
     * treat "unlinked" as an answer without the thrown exception marking the surrounding
     * transaction rollback-only.
     */
    @NonNull
    Optional<UUID> findPersonUuidForUser(@NonNull String username);

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
