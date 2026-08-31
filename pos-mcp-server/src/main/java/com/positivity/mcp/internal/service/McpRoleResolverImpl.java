package com.positivity.mcp.internal.service;

import com.positivity.mcp.internal.domain.RoleAuthorities;
import com.positivity.mcp.internal.telemetry.McpRoleResolver;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.NonNull;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;

/**
 * Resolves the persona a caller gets from the roles they hold.
 *
 * <p>Priority used to be a compile-time {@code List.of(...)}, which meant a role added to
 * {@code pos-security-service} resolved to the generic fallback until someone edited Java (#1613).
 * It now comes from the synced snapshot, ordered by the rank stored on the role.
 */
@Service
public class McpRoleResolverImpl implements McpRoleResolver {

    static final String FALLBACK_ROLE = SystemPromptDefaults.ROLE_USER_PROMPT_NAME;

    private final RolePersonaSnapshotHolder snapshotHolder;

    public McpRoleResolverImpl(@NonNull RolePersonaSnapshotHolder snapshotHolder) {
        this.snapshotHolder = snapshotHolder;
    }

    @Override
    public @NonNull String resolvePrimaryRole(@NonNull Authentication authentication) {
        Set<String> userRoles = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(Objects::nonNull)
                .filter(authority -> authority.startsWith(RoleAuthorities.ROLE_PREFIX))
                .collect(Collectors.toSet());
        // An empty snapshot — sync has never succeeded — resolves everyone to the fallback. Degraded
        // but safe: the ROLE layer is persona-only and grants nothing beyond the caller's permissions.
        return snapshotHolder.get().resolvePrimaryRole(userRoles, FALLBACK_ROLE);
    }
}
