package com.positivity.mcp.service;

import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * Authenticated user context made available to orchestration and tools.
 *
 * @param username human-readable principal name from the security context
 * @param userId stable UUID identity from the authenticated token
 * @param primaryRole highest-priority role selected for orchestration; used for
 *     system-prompt/RAG-scope selection and agent-cache namespacing only — it does
 *     <strong>not</strong> gate {@code mcp_tool} candidate selection
 * @param roles all granted role authorities
 * @param authorities all granted authorities, including roles and permissions
 * @param permissionCodes bare {@code domain:resource:action} permission codes (plus the
 *     synthetic {@code AUTHENTICATED} code) derived from {@code perm_bits}; this is the
 *     authorization signal used to gate {@code mcp_tool} candidate selection
 */
public record CurrentUserContext(
        @NonNull String username,
        @NonNull UUID userId,
        @NonNull String primaryRole,
        @NonNull Set<String> roles,
        @NonNull Set<String> authorities,
        @NonNull Set<String> permissionCodes) {}
