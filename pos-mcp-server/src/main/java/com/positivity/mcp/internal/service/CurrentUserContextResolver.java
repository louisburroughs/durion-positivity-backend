package com.positivity.mcp.internal.service;

import com.positivity.mcp.service.CurrentUserContext;
import com.positivity.mcp.service.McpRoleResolver;
import com.positivity.security.common.SecurityContextHelper;
import java.util.Set;
import java.util.TreeSet;
import org.jspecify.annotations.NonNull;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

@Service
public class CurrentUserContextResolver {

    private final McpRoleResolver mcpRoleResolver;

    public CurrentUserContextResolver(@NonNull McpRoleResolver mcpRoleResolver) {
        this.mcpRoleResolver = mcpRoleResolver;
    }

    public @NonNull CurrentUserContext resolve(@NonNull Authentication authentication) {
        String username = SecurityContextHelper.getCurrentUsername()
                .orElseThrow(() -> new IllegalStateException("Authenticated username is required"));
        String primaryRole = mcpRoleResolver.resolvePrimaryRole(authentication);
        Set<String> authorities = new TreeSet<>(SecurityContextHelper.getAuthorities());
        Set<String> roles = authorities.stream()
                .filter(authority -> authority.startsWith("ROLE_"))
                .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
        return new CurrentUserContext(
                username,
                SecurityContextHelper.getCurrentUserIdAsUuidOrThrowIllegalStateException(),
                primaryRole,
                Set.copyOf(roles),
                Set.copyOf(authorities));
    }
}
