package com.positivity.mcp.internal.event;

import org.jspecify.annotations.NonNull;

/**
 * Published after a successful change to runtime agent configuration — a system-prompt write or an
 * {@code mcp_tool_permission} grant/revoke — so cached role agents are rebuilt instead of serving
 * stale tool sets until TTL expiry (#639).
 *
 * <p>Listeners run after the surrounding transaction commits (with fallback execution for
 * non-transactional publishers), so a rebuild triggered by the invalidation always re-reads the
 * committed configuration.
 */
public record AgentCacheInvalidationEvent(
        @NonNull Source source, @NonNull String detail) {

    /** What kind of configuration change triggered the invalidation. */
    public enum Source {
        SYSTEM_PROMPT,
        TOOL_PERMISSION
    }

    public static @NonNull AgentCacheInvalidationEvent systemPromptChanged(@NonNull String promptName) {
        return new AgentCacheInvalidationEvent(Source.SYSTEM_PROMPT, promptName);
    }

    public static @NonNull AgentCacheInvalidationEvent toolPermissionChanged(@NonNull String toolName) {
        return new AgentCacheInvalidationEvent(Source.TOOL_PERMISSION, toolName);
    }
}
