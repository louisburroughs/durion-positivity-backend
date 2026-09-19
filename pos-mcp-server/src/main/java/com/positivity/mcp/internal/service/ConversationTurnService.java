package com.positivity.mcp.internal.service;

import com.positivity.mcp.internal.dto.ChatBlock;
import com.positivity.mcp.internal.exception.ConversationNotFoundException;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * The chat-path seam between {@code McpChatController} and conversation persistence (#2073,
 * anvil decision: "persistence point"). Keeps {@code McpChatController} thin — it forwards the
 * raw request fields here and translates the result into {@code ChatResponse}, with no
 * orchestration or persistence logic of its own.
 *
 * <p>{@code conversationId} is dual-purpose (the chat-memory window key <em>and</em> the
 * persisted conversation id) and is handled per its resolved value:
 *
 * <ul>
 *   <li>{@code null} — starts a new persisted conversation, fresh memory, and returns its id.
 *   <li>a UUID string owned by the caller — reuses that conversation and its memory.
 *   <li>a UUID string not found, or owned by another subject — throws {@link
 *       ConversationNotFoundException} (answered as 404 {@code CONVERSATION_NOT_FOUND}); the
 *       server never creates a new conversation under a caller-chosen id.
 *   <li>a non-UUID string — the deprecated ephemeral isolation key (#1735): memory-only, never
 *       persisted, echoed back unchanged in {@link ChatTurnResult#conversationId()} with {@link
 *       ChatTurnResult#messageId()} {@code null}. Kept so existing non-UUID callers (e.g. the
 *       analytics gate script's {@code {run_id}-{fixture_id}} ids) keep working unchanged.
 * </ul>
 *
 * <p>The turn is persisted (user + assistant messages) in a short transaction <em>after</em> the
 * model returns — no database transaction is held across the LLM call. If the conversation
 * vanished mid-turn (deleted in another tab, or purged), the answer is still returned with {@link
 * ChatTurnResult#messageId()} {@code null} rather than a 404 after the work is already done.
 */
public interface ConversationTurnService {

    /**
     * Runs one chat turn and, unless the ephemeral path applies, persists it.
     *
     * @param conversationId caller-supplied conversation id from {@code ChatRequest}, or {@code
     *     null} to start a new persisted conversation
     * @param message the caller's chat message
     * @throws ConversationNotFoundException when {@code conversationId} is a UUID not found/owned
     *     by the caller
     */
    @NonNull
    ChatTurnResult runTurn(@Nullable String conversationId, @NonNull String message);

    /**
     * Result of one chat turn.
     *
     * @param response full agent response text, unchanged from today's {@code ChatResponse.response}
     * @param blocks typed blocks segmented from {@code response} (may be empty; see plan decision O1)
     * @param conversationId the resolved conversation id: newly created, reused, or (ephemeral path)
     *     the caller's own non-UUID key echoed back unchanged
     * @param messageId id of the persisted assistant message, or {@code null} in the ephemeral path
     *     or when the conversation vanished mid-turn
     */
    record ChatTurnResult(
            @NonNull String response,
            @NonNull List<ChatBlock> blocks,
            @NonNull String conversationId,
            @Nullable UUID messageId) {}
}
