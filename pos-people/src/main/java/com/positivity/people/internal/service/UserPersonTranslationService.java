package com.positivity.people.internal.service;

import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

public interface UserPersonTranslationService {

    @NonNull
    UUID getPersonUuidForUser(@NonNull String username);

    /**
     * The person whose link to this username is still {@code ACTIVE}, or empty when there is
     * none. Two differences from {@link #getPersonUuidForUser}, both required by the one caller
     * that decides authorization with it ({@code WorkSessionAccessPolicy}):
     *
     * <ul>
     * <li>It ignores an {@code INACTIVE} link. That status is a historical association, so a
     * revoked link must not still answer "this caller is that person" and hand back the
     * self-service path (#2062 review).</li>
     * <li>It never throws, so a caller inside a transaction can treat "not linked" as an answer
     * rather than having the thrown exception mark the surrounding transaction rollback-only.</li>
     * </ul>
     */
    @NonNull
    Optional<UUID> findActivePersonUuidForUser(@NonNull String username);

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
