package com.positivity.peoplecontact.internal.security;

/**
 * Permission names this module enforces, as constants rather than string literals at each call
 * site.
 *
 * <h2>Why constants and not literals</h2>
 *
 * A literal is invisible to a reader looking for everywhere a permission is used, and it is one
 * typo away from an authority nobody holds — {@code @PreAuthorize} fails closed, so a misspelling
 * does not break the build or the test suite, it silently locks the endpoint. Naming the permission
 * once means the compiler checks every use of it.
 *
 * <p>The repo-wide permission tooling reads these too:
 * {@code scripts/generate-permissions.sh --sync} resolves constant references when it decides
 * whether a permission is registered in the catalogs, so a permission introduced here is picked up
 * without a manual bit assignment.
 */
public final class PeopleContactPermissions {
    /** Edit organization postal address. */
    public static final String ORGANIZATION_EDIT = "people-contact:organization:edit";

    /** View organization postal address. */
    public static final String ORGANIZATION_VIEW = "people-contact:organization:view";

    /** Create person. */
    public static final String PERSON_CREATE = "people-contact:person:create";

    /** Delete person. */
    public static final String PERSON_DELETE = "people-contact:person:delete";

    /** Edit person. */
    public static final String PERSON_EDIT = "people-contact:person:edit";

    /** View person. */
    public static final String PERSON_VIEW = "people-contact:person:view";

    /** Assign roles to people. */
    public static final String ROLE_ASSIGN = "people-contact:role:assign";

    /** Revoke roles from people. */
    public static final String ROLE_REVOKE = "people-contact:role:revoke";

    /** View role assignments. */
    public static final String ROLE_VIEW = "people-contact:role:view";

    /** View user-person links. */
    public static final String USERLINK_VIEW = "people-contact:userLink:view";

    /** Write user-person links. */
    public static final String USERLINK_WRITE = "people-contact:userLink:write";

    // ── Permissions owned by other domains ──────────────────────────────────────────────
    //
    // Declared here so this module's call sites are constants like every other, but the names
    // belong elsewhere. Their definition, bit assignment and description live with their owner —
    // this is a reference, not a claim of ownership.

    /**
     * Owned by the people domain (pos-people's {@code permissions.yaml}). One permission spans
     * both modules because it answers one question — may this caller read their own row — and
     * ADR-0044 §6 splits that row across the two: identity here, employment there. Two
     * permissions would have to be granted and revoked in lockstep forever.
     */
    public static final String PEOPLE_SELF_VIEW = "people:self:view";

    private PeopleContactPermissions() {
        // Utility class - prevent instantiation
    }
}
