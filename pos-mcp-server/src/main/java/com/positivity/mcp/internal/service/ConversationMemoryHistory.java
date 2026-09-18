package com.positivity.mcp.internal.service;

import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.ai.chat.messages.Message;

/**
 * Reloads a persisted conversation's chat-memory window (#2073, anvil decision 7).
 *
 * <p>The chat memory held by the orchestration layer is an in-process cache: it is empty after a
 * restart, an eviction or a TTL expiry. When that cache misses for a persisted conversation, the
 * orchestration layer asks this seam for the conversation's latest turns (the stored {@code
 * mcp_message.content}) so the model continues the conversation instead of starting over. The
 * orchestration package may not reach repositories directly (module {@code ArchitectureTest}), which
 * is why this lives in {@code internal.service}.
 */
public interface ConversationMemoryHistory {

    /**
     * The newest {@code limit} turns of {@code conversationId}, oldest first, as chat-memory messages
     * ({@code user} → user message, {@code assistant} → assistant message). Only a conversation the
     * authenticated caller owns within the bound tenant is read; anything else — unknown id, another
     * subject's conversation, no authenticated caller on this thread — yields an empty list, the same
     * fresh memory the caller would have had before #2073.
     */
    @NonNull
    List<Message> recentTurns(@NonNull UUID conversationId, int limit);
}
