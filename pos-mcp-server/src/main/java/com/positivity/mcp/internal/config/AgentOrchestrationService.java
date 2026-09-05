package com.positivity.mcp.internal.config;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Public API for per-user assistant runtime session management.
 * Implementations maintain a per-user agent cache with role-aware tool sets.
 */
public interface AgentOrchestrationService {

    /**
     * Returns or creates a PosAssistant proxy for the given user and role.
     * Multiple chat turns for the same user reuse the same agent instance.
     */
    @NonNull
    String chat(@NonNull CurrentUserContext currentUserContext, @NonNull String message);

    /**
     * Chat within a named conversation.
     *
     * <p>#1735: conversation memory was keyed on {@code (username, role)} alone, so every request
     * from one actor shared a single history. That is right for a genuine chat session and wrong
     * for a caller issuing independent questions — the analytics gate's twelve questions ran as one
     * twelve-turn conversation, and each answer could be shaped by the eleven before it. A caller
     * that wants isolation passes a distinct id per question; a caller that wants a running
     * conversation passes a stable one, or none at all and keeps the pre-#1735 behaviour.
     *
     * @param conversationId opaque caller-chosen id, or null for the shared per-(user, role) memory
     */
    default String chat(
            @NonNull CurrentUserContext currentUserContext, @NonNull String message, @Nullable String conversationId) {
        return chat(currentUserContext, message);
    }

    /**
     * Evicts the cached agent for the given user.
     * Call on role changes or explicit logout.
     */
    void evict(@NonNull String userId);
}
