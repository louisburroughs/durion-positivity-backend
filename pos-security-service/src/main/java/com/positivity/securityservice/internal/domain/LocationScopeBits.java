package com.positivity.securityservice.internal.domain;

import com.positivity.securityservice.internal.enums.LocationHierarchy;
import com.positivity.securityservice.internal.enums.LocationScope;
import com.positivity.securityservice.internal.enums.PermissionCode;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.NonNull;

/**
 * The two location-scope bitsets of an access token (ADR-0061 §2, #1868), composed from the
 * per-role grants a principal holds. Pure: no Spring, no I/O.
 *
 * <p>Composition rules, in order:
 *
 * <ol>
 *   <li><b>Union semantics.</b> A permission granted by <em>any</em> {@link LocationScope#ALL}
 *       role is global and appears in neither bitset — the broader grant wins, consistent with how
 *       {@code perm_bits} composes.</li>
 *   <li>Otherwise the permission is location-scoped. It is set in {@link #financial()} if any
 *       granting {@code LOCATION} role is {@link LocationHierarchy#FINANCIAL}, and in
 *       {@link #other()} if any is {@link LocationHierarchy#OTHER}. A permission may be in
 *       <em>both</em> — one role each way — which is why there are two bitsets rather than one
 *       bitset plus a dimension flag.</li>
 * </ol>
 *
 * <p>Permission names that are not in the compiled {@link PermissionCode} catalog are dropped,
 * exactly as {@code perm_bits} drops them: a name with no bit index cannot be encoded.
 *
 * @param financial permissions scoped along the {@code FINANCIAL} dimension
 * @param other     permissions scoped along the {@code OTHER} dimension
 */
public record LocationScopeBits(
        @NonNull Set<PermissionCode> financial, @NonNull Set<PermissionCode> other) {

    public LocationScopeBits {
        financial = Set.copyOf(financial);
        other = Set.copyOf(other);
    }

    public static @NonNull LocationScopeBits compose(@NonNull List<RoleGrant> grants) {
        Set<String> global = new HashSet<>();
        for (RoleGrant grant : grants) {
            if (grant.locationScope() == LocationScope.ALL) {
                global.addAll(grant.permissionNames());
            }
        }

        Set<PermissionCode> financial = EnumSet.noneOf(PermissionCode.class);
        Set<PermissionCode> other = EnumSet.noneOf(PermissionCode.class);
        for (RoleGrant grant : grants) {
            if (grant.locationScope() != LocationScope.LOCATION) {
                continue;
            }
            Set<PermissionCode> target = grant.locationHierarchy() == LocationHierarchy.FINANCIAL ? financial : other;
            for (String name : grant.permissionNames()) {
                if (global.contains(name)) {
                    continue;
                }
                PermissionCode.fromCode(name).ifPresent(target::add);
            }
        }
        return new LocationScopeBits(financial, other);
    }

    /** True when neither bitset holds a permission: the caller's reach is unrestricted. */
    public boolean isEmpty() {
        return financial.isEmpty() && other.isEmpty();
    }
}
