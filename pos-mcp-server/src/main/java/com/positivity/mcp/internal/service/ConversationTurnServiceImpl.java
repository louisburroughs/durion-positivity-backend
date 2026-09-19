package com.positivity.mcp.internal.service;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.positivity.mcp.internal.config.AgentOrchestrationService;
import com.positivity.mcp.internal.config.CurrentUserContext;
import com.positivity.mcp.internal.dto.ChatBlock;
import com.positivity.mcp.internal.exception.ConversationBusyException;
import com.positivity.mcp.internal.exception.ConversationNotFoundException;
import com.positivity.shared.id.UUIDv7Generator;
import com.positivity.tenancy.TenantContext;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
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
 *
 * <p>Turns on one persisted conversation are serialized in-process: after the ownership check the
 * turn takes the conversation's lock with {@link ReentrantLock#tryLock()} and holds it across the
 * model call and the persistence, releasing it in {@code finally}. A second concurrent turn on the
 * same conversation is rejected with {@link ConversationBusyException} (409 {@code
 * CONVERSATION_BUSY}) instead of running against the same model memory and persisting its messages
 * in completion order. No database transaction is held across the model call. The lock is taken
 * only after ownership is confirmed, so a busy answer never reveals another subject's conversation.
 * A new conversation (fresh server-generated id) and the deprecated non-UUID ephemeral key need no
 * lock: nobody else can name the former, and the latter persists nothing.
 *
 * <p>The lock is process-local. pos-mcp-server runs as a single instance (no replica
 * configuration in docker-compose or the alpha stack); a multi-replica deployment would need a
 * durable per-conversation lease (for example a lease column or advisory lock) in place of this
 * one. Locks are held weakly: an idle conversation's lock is reclaimed by the garbage collector,
 * while a lock in use is strongly reachable from the turn holding it, so the map stays bounded by
 * the turns in flight.
 */
@Service
public class ConversationTurnServiceImpl implements ConversationTurnService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ConversationTurnServiceImpl.class);

    private final AgentOrchestrationService agentOrchestrationService;
    private final CurrentUserContextResolver currentUserContextResolver;
    private final ConversationStore store;
    private final LoadingCache<TurnLockKey, ReentrantLock> turnLocks =
            Caffeine.newBuilder().weakValues().build(key -> new ReentrantLock());

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

        if (persistedId == null) {
            return persistedTurn(user, UUIDv7Generator.generate(), true, message);
        }
        UUID resolvedId = persistedId;
        if (!store.isOwned(resolvedId, user.userId())) {
            throw new ConversationNotFoundException("Conversation not found: " + conversationId);
        }
        ReentrantLock lock =
                turnLocks.get(new TurnLockKey(TenantContext.current().orElse(null), resolvedId));
        if (!lock.tryLock()) {
            throw new ConversationBusyException(
                    "A turn is already running on conversation " + resolvedId + "; retry once it has answered");
        }
        try {
            return persistedTurn(user, resolvedId, false, message);
        } finally {
            lock.unlock();
        }
    }

    /** Runs the model and persists the turn; the caller holds the conversation's lock when it is not new. */
    private @NonNull ChatTurnResult persistedTurn(
            @NonNull CurrentUserContext user,
            @NonNull UUID resolvedId,
            boolean newConversation,
            @NonNull String message) {
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

    /** Per-conversation lock key; the tenant is part of it so equal ids in two tenants never contend. */
    private record TurnLockKey(
            @Nullable UUID tenantId, @NonNull UUID conversationId) {}

    private static @NonNull Authentication currentAuthentication() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            throw new IllegalStateException("An authenticated caller is required for a chat turn");
        }
        return authentication;
    }
}
