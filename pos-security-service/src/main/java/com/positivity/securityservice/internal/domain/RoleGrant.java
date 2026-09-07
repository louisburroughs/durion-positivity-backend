package com.positivity.securityservice.internal.domain;

import com.positivity.securityservice.internal.enums.LocationHierarchy;
import com.positivity.securityservice.internal.enums.LocationScope;
import java.util.Set;
import org.jspecify.annotations.NonNull;

/**
 * One role's persisted grants together with its location reach (ADR-0061 §1/§2, #1868).
 *
 * <p>{@code RoleAuthorityService.expandRolesToAuthorities} collapses every role a principal holds
 * into one authority set, which is right for {@code perm_bits} but loses the per-role scope the
 * {@code loc_fin_bits} / {@code loc_oth_bits} claims are composed from. This is the un-collapsed
 * form: the token issuer applies {@link LocationScopeBits#compose(java.util.List)} to it.
 *
 * @param roleName        normalized role name (upper case, no {@code ROLE_} prefix)
 * @param locationScope   whether the role's grants reach everywhere or only assigned nodes
 * @param locationHierarchy which parent dimension a {@code LOCATION} role is evaluated along
 * @param permissionNames the permission names granted through {@code role_permissions}
 */
public record RoleGrant(
        @NonNull String roleName,
        @NonNull LocationScope locationScope,
        @NonNull LocationHierarchy locationHierarchy,
        @NonNull Set<String> permissionNames) {

    public RoleGrant {
        permissionNames = Set.copyOf(permissionNames);
    }
}
