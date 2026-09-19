package com.positivity.mcp.internal.service;

import com.positivity.mcp.internal.config.AgentOrchestrationService;
import com.positivity.mcp.internal.config.ConversationProperties;
import com.positivity.mcp.internal.dto.AppendMessageRequest;
import com.positivity.mcp.internal.dto.ChatBlock;
import com.positivity.mcp.internal.dto.ConversationDetail;
import com.positivity.mcp.internal.dto.ConversationMessage;
import com.positivity.mcp.internal.dto.ConversationPolicy;
import com.positivity.mcp.internal.dto.ConversationSummary;
import com.positivity.mcp.internal.dto.CreateConversationRequest;
import com.positivity.mcp.internal.dto.UpdateConversationRequest;
import com.positivity.mcp.internal.entity.McpConversation;
import com.positivity.mcp.internal.entity.McpMessage;
import com.positivity.mcp.internal.enums.ConversationMessageRole;
import com.positivity.mcp.internal.exception.ConversationNotFoundException;
import com.positivity.security.common.SecurityContextHelper;
import jakarta.validation.ConstraintViolationException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * {@link ConversationService} over {@link ConversationStore} (#2073).
 *
 * <p>The owner is the authenticated token subject ({@link
 * SecurityContextHelper#getCurrentUserIdAsUuidOrThrowIllegalStateException()}, the same value as
 * {@code CurrentUserContext.userId()}); the tenant is whatever {@code TenantContext} bound for the
 * request. Neither ever comes from an argument. A conversation id that is unknown or owned
 * by another subject throws {@link ConversationNotFoundException} with the same message in every case,
 * so the 404 reveals nothing.
 */
@Service
public class ConversationServiceImpl implements ConversationService {

    /** Serialized size limit of an appended turn (blocks JSON plus content), in bytes. */
    static final int MAX_APPENDED_TURN_BYTES = 256 * 1024;

    private final ConversationStore store;
    private final ConversationProperties properties;
    private final ObjectProvider<AgentOrchestrationService> agentOrchestrationService;

    public ConversationServiceImpl(
            @NonNull ConversationStore store,
            @NonNull ConversationProperties properties,
            @NonNull ObjectProvider<AgentOrchestrationService> agentOrchestrationService) {
        this.store = store;
        this.properties = properties;
        this.agentOrchestrationService = agentOrchestrationService;
    }

    @Override
    public @NonNull List<ConversationSummary> list() {
        return store.rail(currentOwner()).stream()
                .map(ConversationServiceImpl::toSummary)
                .toList();
    }

    @Override
    public @NonNull ConversationDetail create(@NonNull CreateConversationRequest request) {
        McpConversation created = store.create(currentOwner(), normalizeTitle(request.title()));
        return toDetail(created, List.of());
    }

    @Override
    public @NonNull ConversationDetail get(@NonNull UUID id) {
        return store.findWithMessages(id, currentOwner())
                .map(found -> toDetail(found.conversation(), found.messages()))
                .orElseThrow(() -> notFound(id));
    }

    @Override
    public @NonNull ConversationDetail update(@NonNull UUID id, @NonNull UpdateConversationRequest request) {
        return store.update(id, currentOwner(), normalizeTitle(request.title()), request.pinned())
                .map(found -> toDetail(found.conversation(), found.messages()))
                .orElseThrow(() -> notFound(id));
    }

    @Override
    public void delete(@NonNull UUID id) {
        if (!store.delete(id, currentOwner())) {
            throw notFound(id);
        }
        evictMemory(List.of(id));
    }

    @Override
    public void deleteAll() {
        evictMemory(store.deleteAll(currentOwner()));
    }

    @Override
    public @NonNull ConversationMessage appendMessage(@NonNull UUID id, @NonNull AppendMessageRequest request) {
        ConversationMessageRole role = parseRole(request.role());
        List<ChatBlock> blocks = List.copyOf(request.blocks());
        String content = request.content() != null && !request.content().isBlank()
                ? request.content()
                : ConversationText.contentFromBlocks(blocks);
        if (content.isBlank()) {
            throw invalid("content is required when no block carries text");
        }
        String blocksJson = store.serializeBlocks(blocks);
        long size = (long) blocksJson.getBytes(StandardCharsets.UTF_8).length
                + content.getBytes(StandardCharsets.UTF_8).length;
        if (size > MAX_APPENDED_TURN_BYTES) {
            throw invalid("message exceeds " + MAX_APPENDED_TURN_BYTES + " bytes serialized");
        }
        McpMessage stored = store.appendClientMessage(id, currentOwner(), role, content, blocksJson)
                .orElseThrow(() -> notFound(id));
        return toMessage(stored, blocks);
    }

    @Override
    public @NonNull ConversationPolicy policy() {
        return new ConversationPolicy(properties.retentionDays(), true);
    }

    /**
     * Drops the chat memory of deleted conversations. Runs after the store's delete transaction has
     * committed, so a turn racing the delete cannot re-cache content the database no longer holds.
     */
    private void evictMemory(@NonNull Collection<UUID> conversationIds) {
        AgentOrchestrationService orchestration = agentOrchestrationService.getIfAvailable();
        if (orchestration == null) {
            return;
        }
        conversationIds.forEach(id -> orchestration.evictConversation(id.toString()));
    }

    private static @NonNull UUID currentOwner() {
        return SecurityContextHelper.getCurrentUserIdAsUuidOrThrowIllegalStateException();
    }

    /** Trimmed title, 1..120 characters; {@code null} passes through as "not supplied". */
    private static @Nullable String normalizeTitle(@Nullable String title) {
        if (title == null) {
            return null;
        }
        String trimmed = title.strip();
        if (trimmed.isEmpty() || trimmed.length() > McpConversation.TITLE_MAX_LENGTH) {
            throw invalid("title must be 1.." + McpConversation.TITLE_MAX_LENGTH + " characters after trimming");
        }
        return trimmed;
    }

    private static @NonNull ConversationMessageRole parseRole(@NonNull String role) {
        try {
            return ConversationMessageRole.fromWireValue(role);
        } catch (IllegalArgumentException exception) {
            throw invalid("role must be 'user' or 'assistant'");
        }
    }

    /** Answered as 400 {@code VALIDATION_ERROR} by the conversation controller's exception handler. */
    private static @NonNull ConstraintViolationException invalid(@NonNull String message) {
        return new ConstraintViolationException(message, Set.of());
    }

    private static @NonNull ConversationNotFoundException notFound(@NonNull UUID id) {
        return new ConversationNotFoundException("Conversation not found: " + id);
    }

    private static @NonNull ConversationSummary toSummary(@NonNull McpConversation conversation) {
        return new ConversationSummary(
                conversation.getId(),
                conversation.getTitle(),
                conversation.getPreview(),
                instant(conversation.getCreatedAt()),
                instant(conversation.getUpdatedAt()),
                conversation.isPinned());
    }

    private @NonNull ConversationDetail toDetail(
            @NonNull McpConversation conversation, @NonNull List<McpMessage> messages) {
        return new ConversationDetail(
                conversation.getId(),
                conversation.getTitle(),
                conversation.getPreview(),
                instant(conversation.getCreatedAt()),
                instant(conversation.getUpdatedAt()),
                conversation.isPinned(),
                messages.stream()
                        .map(message ->
                                toMessage(message, store.deserializeBlocks(message.getId(), message.getBlocks())))
                        .toList());
    }

    private static @NonNull ConversationMessage toMessage(
            @NonNull McpMessage message, @NonNull List<ChatBlock> blocks) {
        return new ConversationMessage(
                message.getId(),
                message.getRole().wireValue(),
                instant(message.getCreatedAt()),
                blocks,
                message.getContent());
    }

    private static @NonNull Instant instant(@NonNull OffsetDateTime timestamp) {
        return timestamp.toInstant();
    }
}
