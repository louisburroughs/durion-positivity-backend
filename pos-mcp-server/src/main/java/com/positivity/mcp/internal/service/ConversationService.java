package com.positivity.mcp.internal.service;

import com.positivity.mcp.internal.dto.AppendMessageRequest;
import com.positivity.mcp.internal.dto.ConversationDetail;
import com.positivity.mcp.internal.dto.ConversationMessage;
import com.positivity.mcp.internal.dto.ConversationPolicy;
import com.positivity.mcp.internal.dto.ConversationSummary;
import com.positivity.mcp.internal.dto.CreateConversationRequest;
import com.positivity.mcp.internal.dto.UpdateConversationRequest;
import com.positivity.mcp.internal.exception.ConversationNotFoundException;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * Conversation history CRUD for the assistant modal (#2073).
 *
 * <p>Every method resolves tenant ({@code tid}) and owner ({@code sub}) from the security/tenancy
 * context itself (ADR-0062) — no method takes a tenant or owner argument, so a caller cannot
 * (even by mistake) pass another subject's identity through the API layer. An id that does not
 * belong to the resolved tenant/owner is indistinguishable from one that never existed:
 * implementations throw {@link ConversationNotFoundException} (never a permission failure) so
 * the controller answers a plain 404.
 *
 * <p>{@code list()} is capped at 200 rows (no pagination), pinned first, then {@code updatedAt}
 * descending. Retention (days idle before an unpinned conversation is purged; pinned
 * conversations are exempt) is exposed once by {@link #policy()} rather than per row, since it is
 * the same server-wide value for every conversation.
 */
public interface ConversationService {

    /** Caller's conversations, pinned first then {@code updatedAt} descending, capped at 200 rows. */
    @NonNull
    List<ConversationSummary> list();

    /** Starts a new, empty conversation, optionally with an initial title. */
    @NonNull
    ConversationDetail create(@NonNull CreateConversationRequest request);

    /**
     * A single conversation with its full message history.
     *
     * @throws ConversationNotFoundException when {@code id} does not belong to the caller
     */
    @NonNull
    ConversationDetail get(@NonNull UUID id);

    /**
     * Applies a partial update (title and/or pinned state); either field may be omitted, and a
     * request with neither field set is a no-op.
     *
     * @throws ConversationNotFoundException when {@code id} does not belong to the caller
     */
    @NonNull
    ConversationDetail update(@NonNull UUID id, @NonNull UpdateConversationRequest request);

    /**
     * Deletes one conversation and its messages.
     *
     * @throws ConversationNotFoundException when {@code id} does not belong to the caller
     */
    void delete(@NonNull UUID id);

    /** Deletes every conversation the caller owns. A no-op (still succeeds) when there are none. */
    void deleteAll();

    /**
     * Appends a client-authored turn to an existing conversation. Distinct from the persistence
     * {@code POST /mcp/chat} performs itself — this is for a caller appending a turn directly
     * (localStorage import, or a non-chat caller), never for re-appending a turn the chat endpoint
     * already persisted.
     *
     * @throws ConversationNotFoundException when {@code id} does not belong to the caller
     */
    @NonNull
    ConversationMessage appendMessage(@NonNull UUID id, @NonNull AppendMessageRequest request);

    /** Server-wide retention policy (days idle before purge; whether pinned conversations are exempt). */
    @NonNull
    ConversationPolicy policy();
}
