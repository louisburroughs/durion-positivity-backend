package com.positivity.mcp.service;

import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * Authenticated user context made available to orchestration and tools.
 *
 * @param username human-readable principal name from the security context
 * @param userId stable UUID identity from the authenticated token
 * @param primaryRole highest-priority role selected for orchestration
 * @param roles all granted role authorities
 * @param authorities all granted authorities, including roles and permissions
 */
public record CurrentUserContext(
        @NonNull String username,
        @NonNull UUID userId,
        @NonNull String primaryRole,
        @NonNull Set<String> roles,
        @NonNull Set<String> authorities) {}
