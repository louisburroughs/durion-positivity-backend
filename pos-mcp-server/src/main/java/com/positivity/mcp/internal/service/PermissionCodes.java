package com.positivity.mcp.internal.service;

import java.util.LinkedHashSet;
import java.util.Set;
import org.jspecify.annotations.NonNull;

/**
 * Derives the {@code permissionCodes} gating signal for MCP tool selection from a
 * caller's Spring Security authorities.
 *
 * <p>{@code GatewayAuthoritiesFilter.expandAuthority()} expands each {@code PERM_<code>}
 * authority decoded from the gateway's {@code X-Perm-Bits} header into both the raw
 * {@code PERM_<code>} form and the bare {@code domain:resource:action} form. This helper
 * extracts those bare forms, which match the {@code mcp_tool_permission.permission_code}
 * values seeded by {@code V18__seed_facade_tool_permissions.sql}.
 *
 * <p>This gating signal is intentionally narrower than "whatever the caller can do":
 * it can only carry bare permission codes plus the synthetic {@link #AUTHENTICATED}
 * sentinel. When a downstream endpoint is guarded with {@code isAuthenticated()},
 * has no {@code @PreAuthorize}, or uses only {@code hasRole(...)} predicates, there
 * is no normal permission code for the tool-selection layer to mirror, so the seed
 * falls back to {@code AUTHENTICATED} instead.
 */
public final class PermissionCodes {

    private static final String ROLE_PREFIX = "ROLE_";
    private static final String PERM_PREFIX = "PERM_";

    /**
     * Synthetic permission code included for every authenticated caller, regardless of
     * their granted authorities. Used to gate tools whose downstream endpoints require
     * only authentication, have no permission-coded {@code @PreAuthorize}, or expose
     * only role predicates that the tool-selection layer cannot translate into a
     * stable {@code mcp_tool_permission.permission_code}.
     */
    public static final String AUTHENTICATED = "AUTHENTICATED";

    private PermissionCodes() {}

    public static @NonNull Set<String> extract(@NonNull Set<String> authorities) {
        Set<String> permissionCodes = new LinkedHashSet<>();
        for (String authority : authorities) {
            if (!authority.startsWith(ROLE_PREFIX) && !authority.startsWith(PERM_PREFIX)) {
                permissionCodes.add(authority);
            }
        }
        permissionCodes.add(AUTHENTICATED);
        return Set.copyOf(permissionCodes);
    }
}
