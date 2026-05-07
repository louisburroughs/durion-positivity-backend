package com.positivity.mcp.internal.service;

import com.positivity.mcp.service.McpRoleResolver;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.NonNull;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;

@Service
public class McpRoleResolverImpl implements McpRoleResolver {

    static final String FALLBACK_ROLE = "ROLE_USER";

    private static final List<String> ROLE_PRIORITY = List.of(
            "ROLE_ADMIN", "ROLE_MANAGER", "ROLE_SERVICE_WRITER", "ROLE_CASHIER", "ROLE_SUPPLIER", "ROLE_TECHNICIAN");

    @Override
    public @NonNull String resolvePrimaryRole(@NonNull Authentication authentication) {
        Set<String> userRoles = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(Objects::nonNull)
                .filter(a -> a.startsWith("ROLE_"))
                .collect(Collectors.toSet());
        return ROLE_PRIORITY.stream().filter(userRoles::contains).findFirst().orElse(FALLBACK_ROLE);
    }
}
