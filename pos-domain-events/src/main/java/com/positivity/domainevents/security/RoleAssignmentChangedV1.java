package com.positivity.domainevents.security;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Fact: a user's role assignment was granted or revoked (ADR-0044, issue #2160).
 *
 * <p>Published by pos-security-service on {@code security.events.v1} with
 * {@code eventType = "security.role-assignment.changed"} whenever {@code UserRoleGrantServiceImpl}
 * grants or revokes an assignment. pos-security-service is the source of truth for role
 * assignments but previously exposed no fact about them, so any other service needing to show who
 * holds which role had to call it back per person — this event exists so a consumer can instead
 * maintain its own replica.
 *
 * <p><b>{@code username} is carried deliberately, not merely {@code userId}, and this is the
 * single most important thing about this payload — do not "clean up" {@code username} as
 * redundant with {@code userId} without reading this note first.</b> Consumers do not all key
 * people the way pos-security-service does. pos-people, in particular, keys its employees by
 * {@code personId} and holds the {@code personId}-to-username mapping in its own
 * {@code ExtUserLinkReplica}, which has no {@code userId} column at all. Without {@code username}
 * on this event, a consumer in that position could not resolve a row to a person from the event
 * alone and would have to call back into pos-security-service per assignment to look the username
 * up — which is exactly the per-person call pattern this event exists to eliminate. Dropping
 * {@code username} would silently reintroduce that N+1 for the consumer this event was written
 * for.
 *
 * <p>Carries the assignment's current state rather than a delta, so a consumer that missed an
 * earlier message still converges on the right answer from any single one it does receive, and
 * reprocessing the same message twice is a no-op.
 *
 * <p>{@code RoleAssignment} carries no location scope of its own — the {@code scope_type} column
 * and {@code role_assignment_scope_locations} table were dropped by
 * {@code V38__drop_role_assignment_scope.sql} (#1875); location reach is a property of the
 * {@code Role} instead, combined with pos-people's staffing assignment (ADR-0061 §1). This event
 * is a fact about the assignment as pos-security-service stores it, so it carries no scope or
 * location fields — a consumer that needs a role's location reach reads it off the role, not off
 * this event.
 *
 * <p>{@code effectiveStartDate} and {@code effectiveEndDate} are {@link LocalDateTime}, matching
 * the type {@code RoleAssignment} stores them as (no zone offset persisted); {@code revokedAt} is
 * an {@link Instant} for the same reason, matching the entity's own type for when the revocation
 * was entered.
 *
 * @param assignmentId the assignment's id, and the envelope's aggregateId
 * @param userId the assigned user's id
 * @param username the assigned user's username — see the class-level note; required so a consumer
 *                 keyed by something other than {@code userId} can resolve this event without a
 *                 callback
 * @param roleId the assigned role's id
 * @param roleName the assigned role's name, unprefixed and upper-case as stored by the owner
 * @param effectiveStartDate when this assignment becomes or became effective
 * @param effectiveEndDate when this assignment's effective window ends; null while open-ended
 * @param revokedAt when the revocation was entered, independent of {@code effectiveEndDate} which
 *                  may be backdated or scheduled ahead; null while the assignment stands
 * @param tenantId the owning tenant, carried on the payload itself (in addition to the envelope's
 *                 own {@code tenantId}) so a consumer building a replica row has it without
 *                 reaching into envelope fields
 */
public record RoleAssignmentChangedV1(
        @NonNull UUID assignmentId,
        @NonNull UUID userId,
        @NonNull String username,
        @NonNull UUID roleId,
        @NonNull String roleName,
        @NonNull LocalDateTime effectiveStartDate,
        @Nullable LocalDateTime effectiveEndDate,
        @Nullable Instant revokedAt,
        @Nullable UUID tenantId) {

    public static final String EVENT_TYPE = "security.role-assignment.changed";
    public static final int SCHEMA_VERSION = 1;

    public RoleAssignmentChangedV1 {
        if (assignmentId == null) {
            throw new IllegalArgumentException("assignmentId must not be null");
        }
        if (userId == null) {
            throw new IllegalArgumentException("userId must not be null");
        }
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("username must not be blank");
        }
        if (roleId == null) {
            throw new IllegalArgumentException("roleId must not be null");
        }
        if (roleName == null || roleName.isBlank()) {
            throw new IllegalArgumentException("roleName must not be blank");
        }
        if (effectiveStartDate == null) {
            throw new IllegalArgumentException("effectiveStartDate must not be null");
        }
    }
}
