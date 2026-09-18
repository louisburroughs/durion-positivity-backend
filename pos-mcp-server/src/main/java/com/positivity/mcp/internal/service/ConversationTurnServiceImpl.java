package com.positivity.mcp.internal.service;

import com.positivity.mcp.internal.config.AgentOrchestrationService;
import com.positivity.mcp.internal.config.CurrentUserContext;
import com.positivity.mcp.internal.dto.ChatBlock;
import com.positivity.mcp.internal.exception.ConversationNotFoundException;
import com.positivity.shared.id.UUIDv7Generator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

/**
 * {@link ConversationTurnService} (#2073, anvil decisions 8-10, user decision U1).
 *
 * <p>Sequence of one turn: resolve the conversation (read-only), run the model with the
 * conversation id as the memory key's conversation part, segment the answer, then persist the user
 * and assistant messages in one short transaction ({@link ConversationStore#recordChatTurn}). No
 * transaction is open while the model runs, and nothing is written when the model call throws
 * (including the 429 rate limit): a new conversation's id is pre-assigned and its row is inserted
 * together with the turn's messages.
 */
@Service
public class ConversationTurnServiceImpl implements ConversationTurnService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ConversationTurnServiceImpl.class);

    private final AgentOrchestrationService agentOrchestrationService;
    private final CurrentUserContextResolver currentUserContextResolver;
    private final ConversationStore store;

    public ConversationTurnServiceImpl(
            @NonNull AgentOrchestrationService agentOrchestrationService,
            @NonNull CurrentUserContextResolver currentUserContextResolver,
            @NonNull ConversationStore store) {
        this.agentOrchestrationService = agentOrchestrationService;
        this.currentUserContextResolver = currentUserContextResolver;
        this.store = store;
    }

    @Override
    public @NonNull ChatTurnResult runTurn(@Nullable String conversationId, @NonNull String message) {
        CurrentUserContext user = currentUserContextResolver.resolve(currentAuthentication());
        LOGGER.debug(
                "MCP chat selected userContext username={} userId={} selectedRole={} roleCount={} authorityCount={} fallback={}",
                user.username(),
                user.userId(),
                user.primaryRole(),
                user.roles().size(),
                user.authorities().size(),
                "ROLE_USER".equals(user.primaryRole()));

        boolean absent = conversationId == null || conversationId.isBlank();
        UUID persistedId = absent ? null : ConversationIds.parseCanonical(conversationId);
        if (!absent && persistedId == null) {
            return ephemeralTurn(user, conversationId, message);
        }

        boolean newConversation = persistedId == null;
        UUID resolvedId;
        if (newConversation) {
            resolvedId = UUIDv7Generator.generate();
        } else {
            resolvedId = persistedId;
            if (!store.isOwned(resolvedId, user.userId())) {
                throw new ConversationNotFoundException("Conversation not found: " + conversationId);
            }
        }

        String response = agentOrchestrationService.chat(user, message, resolvedId.toString());
        List<ChatBlock> blocks = ChatBlockSegmenter.segment(response);

        Optional<UUID> assistantMessageId = store.recordChatTurn(
                resolvedId,
                newConversation,
                user.userId(),
                message,
                List.of(new ChatBlock.TextBlock(message)),
                response,
                blocks);
        if (assistantMessageId.isEmpty()) {
            LOGGER.info(
                    "Conversation {} was deleted or purged during the turn; answer returned without persisting it",
                    resolvedId);
        }
        return new ChatTurnResult(response, blocks, resolvedId.toString(), assistantMessageId.orElse(null));
    }

    /**
     * The deprecated #1735 path: a non-UUID {@code conversationId} isolates the model's memory and
     * nothing else — no conversation row, no messages, the caller's key echoed back.
     */
    private @NonNull ChatTurnResult ephemeralTurn(
            @NonNull CurrentUserContext user, @NonNull String conversationId, @NonNull String message) {
        String response = agentOrchestrationService.chat(user, message, conversationId);
        return new ChatTurnResult(response, ChatBlockSegmenter.segment(response), conversationId, null);
    }

    private static @NonNull Authentication currentAuthentication() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            throw new IllegalStateException("An authenticated caller is required for a chat turn");
        }
        return authentication;
    }
}
