package com.positivity.mcp.internal.config;

import com.positivity.mcp.internal.domain.ChatOutcome;
import com.positivity.mcp.internal.domain.TurnSummary;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
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
    default @NonNull String chat(
            @NonNull CurrentUserContext currentUserContext, @NonNull String message, @Nullable String conversationId) {
        return chat(currentUserContext, message);
    }

    /**
     * Evicts the conversation state and rate counter of {@code username} within the bound tenant
     * (the caches are keyed by the gateway username, not the user id). Call on role changes or
     * explicit logout.
     */
    void evict(@NonNull String username);

    /**
     * #2073: drops the cached chat memory of one conversation within the bound tenant, for every
     * actor and role, after the conversation is deleted or purged. An implementation that holds no
     * conversation memory has nothing to evict; one that does must override this.
     *
     * @param conversationId the conversation id component of the memory key
     */
    default void evictConversation(@NonNull String conversationId) {
        // No conversation memory held by default.
    }

    /**
     * #2075: one chat turn plus its profile-independent summary. {@code assistantMessageId} is
     * the pre-assigned id of the assistant message the caller will persist (null on the
     * ephemeral path); an implementation that records an eval trace stamps it there. The default
     * wraps {@link #chat(CurrentUserContext, String, String)} and reports latency only — no
     * answer path, no answer source, no tool names.
     */
    default @NonNull ChatOutcome chatTurn(
            @NonNull CurrentUserContext currentUserContext,
            @NonNull String message,
            @Nullable String conversationId,
            @Nullable UUID assistantMessageId) {
        long startNanos = System.nanoTime();
        String text = chat(currentUserContext, message, conversationId);
        return new ChatOutcome(
                text,
                new TurnSummary(
                        null,
                        null,
                        List.of(),
                        TurnSummary.clampLatency(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos))));
    }
}
