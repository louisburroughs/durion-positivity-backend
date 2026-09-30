package com.positivity.people.internal.service;

import com.positivity.people.internal.exception.ReplicationPendingCodes;
import com.positivity.web.common.ReplicationPendingException;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * Username to person translation over the user-link replica.
 *
 * <p>The replica fills by event, so a username with no row is either a user who was never linked to
 * a person or a link whose event has not been consumed, and the two cannot be told apart (#1994).
 * A read that applies defaults for a missing link (availability defaulting, #1636) treats the
 * {@link jakarta.persistence.EntityNotFoundException} from {@link #getPersonUuidForUser} as "no
 * assignment" and is unchanged. A read that answers the <em>current user</em> with no default
 * (the {@code /me} endpoints) uses {@link #getPersonUuidForCurrentUser} or
 * {@link #userLinkReplicationPending()}, which answer 503 instead of a bare 404 a caller would
 * read as permanent.
 */
public interface UserPersonTranslationService {

    /**
     * The 503 a current-user read answers while the caller has no row in the user-link replica.
     * The awaited thing is a link for the caller's username, not an entity id, so it carries no
     * {@code referenceId}, and the message names no request value.
     */
    static @NonNull ReplicationPendingException userLinkReplicationPending() {
        return new ReplicationPendingException(
                ReplicationPendingCodes.USER_LINK_REPLICATION_PENDING,
                "The caller's person link has not replicated from People Contact yet; retry shortly");
    }

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
     * Whether the replica holds any link row, active or not, for this username. Non-throwing, so a
     * caller that just saw {@link #findActivePersonUuidForUser} come back empty can tell a caller
     * whose link has not replicated yet (no row: {@link #userLinkReplicationPending()}) from one
     * whose link is present but no longer ACTIVE (a definite 404).
     */
    boolean hasLinkForUser(@NonNull String username);

    /**
     * Resolve the current authenticated user's person id from the security context.
     * @return person UUID linked to the current user
     * @throws org.springframework.web.server.ResponseStatusException 401 when no
     * authenticated user context is present
     * @throws ReplicationPendingException 503 {@code USER_LINK_REPLICATION_PENDING} when the
     * replica holds no link for the user
     */
    @NonNull
    UUID getPersonUuidForCurrentUser();

    @NonNull
    Optional<String> getUsernameForPerson(@NonNull UUID personUuid);

    boolean isUserLinkedToPerson(@NonNull String username, @NonNull UUID personUuid);
}
