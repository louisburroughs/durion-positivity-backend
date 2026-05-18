package com.positivity.mcp.internal.service;

import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.NonNull;

public final class ToolRegistryRoleMapper {

    private static final Map<String, String> REGISTRY_ROLE_ALIASES = Map.ofEntries(
            Map.entry("ROLE_SYSTEM_ADMINISTRATOR", "ROLE_ADMIN"),
            Map.entry("ROLE_LOCATION_MANAGER", "ROLE_MANAGER"),
            Map.entry("ROLE_ACCOUNT_MANAGER", "ROLE_MANAGER"),
            Map.entry("ROLE_ACCOUNTING_ASSOCIATE", "ROLE_MANAGER"),
            Map.entry("ROLE_SERVICE_ADVISOR", "ROLE_SERVICE_WRITER"),
            Map.entry("ROLE_DISPATCHER", "ROLE_SERVICE_WRITER"),
            Map.entry("ROLE_TECHNICIAN", "ROLE_SERVICE_WRITER"));

    private ToolRegistryRoleMapper() {}

    public static @NonNull String normalize(@NonNull String roleName) {
        String normalizedRole = roleName.trim();
        if (normalizedRole.isBlank()) {
            return normalizedRole;
        }
        return Optional.ofNullable(REGISTRY_ROLE_ALIASES.get(normalizedRole)).orElse(normalizedRole);
    }
}
