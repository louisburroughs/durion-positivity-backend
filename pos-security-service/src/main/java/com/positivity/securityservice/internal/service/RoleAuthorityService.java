package com.positivity.securityservice.internal.service;

import com.positivity.securityservice.internal.domain.RoleGrant;
import java.util.List;
import java.util.Set;

/**
 * Resolves the authorities a set of role names carries.
 *
 * <p>Role-to-permission grants are persisted data, held in {@code role_permissions} and
 * provisioned by the {@code R__seed_role_permissions.sql} baseline plus the role-permission
 * admin API. There is no compiled role-to-authority map: a role grants exactly what the
 * database says it grants, so a role with no rows in {@code role_permissions} carries no
 * permission authorities at all.
 */
public interface RoleAuthorityService {

    String ROLE_PREFIX = "ROLE_";

    /**
     * Expand role names to authorities.
     *
     * <p>The result contains the {@code ROLE_}-prefixed authority for every non-blank role
     * name supplied, plus every permission name granted to those roles through
     * {@code role_permissions}. Role names are matched case-insensitively and an existing
     * {@code ROLE_} prefix is stripped before lookup.
     *
     * <p>Fails closed: an unknown role, or a known role with no grants, contributes only its
     * {@code ROLE_} authority.
     *
     * @param roles role names, with or without the {@code ROLE_} prefix; may be null or empty
     * @return the authorities carried by those roles; never null
     */
    Set<String> expandRolesToAuthorities(Set<String> roles);

    /**
     * Resolve the same {@code role_permissions} grants as {@link #expandRolesToAuthorities}, but
     * per role and with each role's location reach attached (ADR-0061 §2, #1868).
     *
     * <p>The token issuer composes the {@code loc_fin_bits} / {@code loc_oth_bits} claims from
     * this; the per-role shape is what lets a permission granted by an {@code ALL} role stay
     * global while the same permission from a {@code LOCATION} role is scoped. Role names are
     * normalized exactly as for {@link #expandRolesToAuthorities}. Unknown roles and roles with
     * no grants contribute no entry, and no {@code ROLE_} pseudo-authority is included — those are
     * not permissions and carry no bit.
     *
     * @param roles role names, with or without the {@code ROLE_} prefix; may be null or empty
     * @return one entry per known, granted role, ordered by normalized role name; never null
     */
    List<RoleGrant> resolveRoleGrants(Set<String> roles);
}
