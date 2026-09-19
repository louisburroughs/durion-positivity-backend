package com.positivity.mcp.internal.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.mcp.internal.domain.TurnSummary;
import com.positivity.mcp.internal.dto.ChatBlock;
import com.positivity.mcp.internal.entity.McpConversation;
import com.positivity.mcp.internal.entity.McpMessage;
import com.positivity.mcp.internal.enums.ConversationMessageOrigin;
import com.positivity.mcp.internal.enums.ConversationMessageRole;
import com.positivity.mcp.internal.repository.McpConversationRepository;
import com.positivity.mcp.internal.repository.McpMessageRepository;
import com.positivity.security.common.SecurityContextHelper;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persistence of assistant conversations (#2073): the one place {@code mcp_conversation} and
 * {@code mcp_message} are read and written.
 *
 * <p>Every method takes the owner explicitly; callers resolve it from the authenticated token
 * ({@code sub}), never from request data. The tenant is never a parameter: Hibernate's
 * {@code @TenantId} and row-level security scope every statement to the bound tenant (ADR-0062).
 * Write paths row-lock the conversation first, so a turn, a delete and the retention purge on the
 * same conversation serialize instead of interleaving.
 *
 * <p>Each method is its own short transaction. None is ever held across a model call: the chat path
 * calls {@link #recordChatTurn} only after the model has answered.
 */
@Service
public class ConversationStore implements ConversationMemoryHistory {

    /** Rows returned by the history rail (no pagination, #2073). */
    static final int RAIL_LIMIT = 200;

    /** Ids per bulk delete statement, well under the driver's bind-parameter ceiling. */
    private static final int DELETE_BATCH_SIZE = 500;

    private static final Logger LOGGER = LoggerFactory.getLogger(ConversationStore.class);
    private static final TypeReference<List<ChatBlock>> BLOCK_LIST = new TypeReference<>() {};
    private static final TypeReference<List<String>> NAME_LIST = new TypeReference<>() {};

    private final McpConversationRepository conversations;
    private final McpMessageRepository messages;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ConversationStore(
            @NonNull McpConversationRepository conversations,
            @NonNull McpMessageRepository messages,
            @NonNull ObjectMapper objectMapper,
            @NonNull Clock clock) {
        this.conversations = conversations;
        this.messages = messages;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /** A conversation and its messages, oldest first. */
    public record ConversationWithMessages(
            @NonNull McpConversation conversation, @NonNull List<McpMessage> messages) {}

    @Transactional(readOnly = true)
    public @NonNull List<McpConversation> rail(@NonNull UUID ownerUserId) {
        return conversations.findRail(ownerUserId, Limit.of(RAIL_LIMIT));
    }

    @Transactional(readOnly = true)
    public boolean isOwned(@NonNull UUID conversationId, @NonNull UUID ownerUserId) {
        return conversations.existsByIdAndOwnerUserId(conversationId, ownerUserId);
    }

    /**
     * Creates an empty conversation.
     *
     * @param title an already-normalized title (1..120 characters), which counts as user-set; {@code
     *     null} to start with {@link ConversationText#DEFAULT_TITLE} and derive from the first user turn
     */
    @Transactional
    public @NonNull McpConversation create(@NonNull UUID ownerUserId, @Nullable String title) {
        McpConversation conversation = new McpConversation();
        conversation.setOwnerUserId(ownerUserId);
        conversation.setTitle(title == null ? ConversationText.DEFAULT_TITLE : title);
        conversation.setTitleUserSet(title != null);
        conversation.setPinned(false);
        return conversations.saveAndFlush(conversation);
    }

    @Transactional(readOnly = true)
    public @NonNull Optional<ConversationWithMessages> findWithMessages(
            @NonNull UUID conversationId, @NonNull UUID ownerUserId) {
        return conversations
                .findByIdAndOwnerUserId(conversationId, ownerUserId)
                .map(conversation -> new ConversationWithMessages(
                        conversation, messages.findByConversationIdOrderByCreatedAtAscIdAsc(conversationId)));
    }

    /**
     * Applies a partial update. A {@code null} field leaves the stored value unchanged; with both
     * {@code null} nothing is written and {@code updatedAt} does not move.
     *
     * @param title an already-normalized title; setting it marks the title user-set
     * @return the conversation with its messages, or empty when the caller does not own it
     */
    @Transactional
    public @NonNull Optional<ConversationWithMessages> update(
            @NonNull UUID conversationId, @NonNull UUID ownerUserId, @Nullable String title, @Nullable Boolean pinned) {
        Optional<McpConversation> owned = conversations.findOwnedForUpdate(conversationId, ownerUserId);
        if (owned.isEmpty()) {
            return Optional.empty();
        }
        McpConversation conversation = owned.get();
        if (title != null) {
            conversation.setTitle(title);
            conversation.setTitleUserSet(true);
        }
        if (pinned != null) {
            conversation.setPinned(pinned);
        }
        McpConversation saved = conversations.saveAndFlush(conversation);
        return Optional.of(new ConversationWithMessages(
                saved, messages.findByConversationIdOrderByCreatedAtAscIdAsc(conversationId)));
    }

    /** Deletes one owned conversation and its messages; {@code false} when the caller does not own it. */
    @Transactional
    public boolean delete(@NonNull UUID conversationId, @NonNull UUID ownerUserId) {
        if (conversations.findOwnedForUpdate(conversationId, ownerUserId).isEmpty()) {
            return false;
        }
        deleteConversations(List.of(conversationId));
        return true;
    }

    /** Deletes every conversation the owner holds; returns the deleted ids (empty when there were none). */
    @Transactional
    public @NonNull List<UUID> deleteAll(@NonNull UUID ownerUserId) {
        List<UUID> ids = conversations.findAllOwnedForUpdate(ownerUserId).stream()
                .map(McpConversation::getId)
                .toList();
        deleteConversations(ids);
        return ids;
    }

    /**
     * Appends a client-authored turn ({@code origin = CLIENT}).
     *
     * @return the stored message, or empty when the caller does not own the conversation
     */
    @Transactional
    public @NonNull Optional<McpMessage> appendClientMessage(
            @NonNull UUID conversationId,
            @NonNull UUID ownerUserId,
            @NonNull ConversationMessageRole role,
            @NonNull String content,
            @NonNull String blocksJson) {
        Optional<McpConversation> owned = conversations.findOwnedForUpdate(conversationId, ownerUserId);
        if (owned.isEmpty()) {
            return Optional.empty();
        }
        McpConversation conversation = owned.get();
        if (role == ConversationMessageRole.USER) {
            deriveTitleFromFirstUserTurn(conversation, content);
        }
        McpMessage stored =
                saveMessage(null, conversationId, role, ConversationMessageOrigin.CLIENT, content, blocksJson, null);
        if (role == ConversationMessageRole.ASSISTANT) {
            updatePreview(conversation, content);
        }
        touch(conversation);
        return Optional.of(stored);
    }

    /**
     * Persists one chat turn ({@code origin = CHAT}): the user message, then the assistant message, and
     * the conversation's title/preview/{@code updatedAt}, in one transaction.
     *
     * <p>Both message ids are pre-assigned by the caller (UUID v7, user first), so the user row sorts
     * before its answer even on a same-instant {@code created_at} tie, and the assistant id can be
     * returned to the client and stamped on the eval trace before anything is written. Only the
     * assistant row carries {@code turnSummary} (#2075).
     *
     * @param newConversation {@code true} when {@code conversationId} was pre-assigned for this turn
     *     and the conversation row is inserted here; {@code false} to append to an existing one
     * @return {@code assistantMessageId}, or empty when an existing conversation is gone (deleted in
     *     another tab, or purged): the turn is then not persisted and nothing is written
     */
    @Transactional
    public @NonNull Optional<UUID> recordChatTurn(
            @NonNull UUID conversationId,
            boolean newConversation,
            @NonNull UUID ownerUserId,
            @NonNull UUID userMessageId,
            @NonNull String userText,
            @NonNull List<ChatBlock> userBlocks,
            @NonNull UUID assistantMessageId,
            @NonNull String assistantText,
            @NonNull List<ChatBlock> assistantBlocks,
            @NonNull TurnSummary turnSummary) {
        McpConversation conversation;
        if (newConversation) {
            conversation = new McpConversation();
            conversation.setId(conversationId);
            conversation.setOwnerUserId(ownerUserId);
            conversation.setTitle(ConversationText.deriveTitle(userText));
            conversation.setTitleUserSet(false);
            conversation.setPinned(false);
            conversation.setPreview(ConversationText.derivePreview(assistantText));
            // Flushed before the messages: they reference it by a plain column, which Hibernate
            // cannot order inserts by.
            conversations.saveAndFlush(conversation);
        } else {
            Optional<McpConversation> owned = conversations.findOwnedForUpdate(conversationId, ownerUserId);
            if (owned.isEmpty()) {
                return Optional.empty();
            }
            conversation = owned.get();
            deriveTitleFromFirstUserTurn(conversation, userText);
            updatePreview(conversation, assistantText);
            touch(conversation);
        }
        saveMessage(
                userMessageId,
                conversationId,
                ConversationMessageRole.USER,
                ConversationMessageOrigin.CHAT,
                userText,
                serializeBlocks(userBlocks),
                null);
        McpMessage assistant = saveMessage(
                assistantMessageId,
                conversationId,
                ConversationMessageRole.ASSISTANT,
                ConversationMessageOrigin.CHAT,
                assistantText,
                serializeBlocks(assistantBlocks),
                turnSummary);
        return Optional.of(assistant.getId());
    }

    /**
     * Sets the owner's rating of a chat-path ({@code origin = CHAT}) assistant message (#2075),
     * replacing any prior rating in full (an absent reason or comment clears the stored one). One bulk
     * {@code UPDATE}: concurrent ratings are last-write-wins. The conversation row is not touched.
     *
     * @return {@code false} when no chat-path assistant message {@code messageId} exists in a
     *     conversation {@code conversationId} that {@code ownerUserId} owns within the bound tenant
     */
    @Transactional
    public boolean setFeedback(
            @NonNull UUID conversationId,
            @NonNull UUID messageId,
            @NonNull UUID ownerUserId,
            @NonNull String rating,
            @Nullable String reason,
            @Nullable String comment) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        return messages.updateOwnedFeedback(
                        conversationId,
                        messageId,
                        ownerUserId,
                        ConversationMessageRole.ASSISTANT,
                        ConversationMessageOrigin.CHAT,
                        rating,
                        reason,
                        comment,
                        now,
                        now)
                == 1;
    }

    /**
     * Withdraws the owner's rating of an assistant message (#2075). Succeeds when the message is
     * reachable even if it was never rated.
     *
     * @return {@code false} when the message is not reachable (same rule as {@link #setFeedback})
     */
    @Transactional
    public boolean clearFeedback(@NonNull UUID conversationId, @NonNull UUID messageId, @NonNull UUID ownerUserId) {
        return messages.updateOwnedFeedback(
                        conversationId,
                        messageId,
                        ownerUserId,
                        ConversationMessageRole.ASSISTANT,
                        ConversationMessageOrigin.CHAT,
                        null,
                        null,
                        null,
                        null,
                        OffsetDateTime.now(clock))
                == 1;
    }

    /**
     * Deletes, within the bound tenant, up to {@code batchSize} unpinned conversations last touched
     * before {@code cutoff}, oldest first, with their messages — one short transaction per batch.
     * Pinned conversations are never purged (user decision U2). Callers repeat until a batch returns
     * fewer than {@code batchSize} ids.
     *
     * @return the purged conversation ids
     */
    @Transactional
    public @NonNull List<UUID> purgeIdleBatch(@NonNull OffsetDateTime cutoff, int batchSize) {
        List<UUID> ids = conversations.findPurgeableForUpdate(cutoff, Limit.of(Math.max(1, batchSize))).stream()
                .map(McpConversation::getId)
                .toList();
        deleteConversations(ids);
        return ids;
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull List<Message> recentTurns(@NonNull UUID conversationId, int limit) {
        Optional<UUID> owner = SecurityContextHelper.getCurrentUserIdAsUuid();
        if (owner.isEmpty()) {
            return List.of();
        }
        List<McpMessage> newestFirst =
                messages.findRecentOwned(conversationId, owner.get(), Limit.of(Math.max(1, limit)));
        List<Message> window = new ArrayList<>(newestFirst.size());
        for (McpMessage message : newestFirst) {
            window.add(
                    message.getRole() == ConversationMessageRole.USER
                            ? new UserMessage(message.getContent())
                            : new AssistantMessage(message.getContent()));
        }
        Collections.reverse(window);
        return window;
    }

    /** Serializes blocks for the {@code blocks} jsonb column. */
    @NonNull
    String serializeBlocks(@NonNull List<ChatBlock> blocks) {
        try {
            return objectMapper.writerFor(BLOCK_LIST).writeValueAsString(blocks);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize conversation message blocks", exception);
        }
    }

    /** Serializes tool names for the {@code tools_called} jsonb column (a JSON array). */
    @NonNull
    String serializeToolNames(@NonNull List<String> toolNames) {
        try {
            return objectMapper.writerFor(NAME_LIST).writeValueAsString(toolNames);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize conversation message tool names", exception);
        }
    }

    /**
     * Reads a stored {@code blocks} column. A row this build cannot read (a block kind since removed)
     * yields an empty list: the message's {@code content} is always stored, and an empty {@code blocks}
     * is the contract's signal to render {@code content} instead (plan decision O1).
     */
    @NonNull
    List<ChatBlock> deserializeBlocks(@NonNull UUID messageId, @Nullable String blocksJson) {
        if (blocksJson == null || blocksJson.isBlank()) {
            return List.of();
        }
        try {
            List<ChatBlock> blocks = objectMapper.readValue(blocksJson, BLOCK_LIST);
            return blocks == null ? List.of() : List.copyOf(blocks);
        } catch (JsonProcessingException exception) {
            LOGGER.warn(
                    "Stored blocks of conversation message {} are unreadable; serving content only: {}",
                    messageId,
                    exception.getOriginalMessage());
            return List.of();
        }
    }

    private void deleteConversations(@NonNull List<UUID> ids) {
        for (int from = 0; from < ids.size(); from += DELETE_BATCH_SIZE) {
            List<UUID> batch = ids.subList(from, Math.min(ids.size(), from + DELETE_BATCH_SIZE));
            messages.deleteByConversationIdIn(batch);
            conversations.deleteByIdIn(batch);
        }
    }

    private void deriveTitleFromFirstUserTurn(@NonNull McpConversation conversation, @NonNull String userText) {
        if (!conversation.isTitleUserSet()
                && !messages.existsByConversationIdAndRole(conversation.getId(), ConversationMessageRole.USER)) {
            conversation.setTitle(ConversationText.deriveTitle(userText));
        }
    }

    private static void updatePreview(@NonNull McpConversation conversation, @NonNull String assistantText) {
        String preview = ConversationText.derivePreview(assistantText);
        if (preview != null) {
            conversation.setPreview(preview);
        }
    }

    /**
     * Marks the conversation modified so auditing moves {@code updatedAt} even when a turn changed
     * neither title nor preview.
     */
    private void touch(@NonNull McpConversation conversation) {
        conversation.setUpdatedAt(OffsetDateTime.now(clock));
    }

    /**
     * @param id a pre-assigned message id, or {@code null} to let the UUID v7 generator assign one
     * @param summary the turn summary (a chat-path assistant turn only), or {@code null}
     */
    private @NonNull McpMessage saveMessage(
            @Nullable UUID id,
            @NonNull UUID conversationId,
            @NonNull ConversationMessageRole role,
            @NonNull ConversationMessageOrigin origin,
            @NonNull String content,
            @NonNull String blocksJson,
            @Nullable TurnSummary summary) {
        McpMessage message = new McpMessage();
        message.setId(id);
        message.setConversationId(conversationId);
        message.setRole(role);
        message.setOrigin(origin);
        message.setContent(content);
        message.setBlocks(blocksJson);
        if (summary != null) {
            message.setAnswerPath(summary.answerPath());
            message.setAnswerSource(summary.answerSource());
            message.setToolsCalled(serializeToolNames(summary.toolsCalled()));
            message.setLatencyMs(summary.latencyMs());
        }
        return messages.saveAndFlush(message);
    }
}
