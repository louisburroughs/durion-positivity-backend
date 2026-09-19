package com.positivity.mcp.internal.repository;

import com.positivity.mcp.internal.entity.McpMessage;
import com.positivity.mcp.internal.enums.ConversationMessageRole;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Conversation messages (#2073), always read through their conversation and the bound tenant. */
public interface McpMessageRepository extends JpaRepository<McpMessage, UUID> {

    /** A conversation's messages, oldest first; the UUID v7 id breaks a same-instant tie in insert order. */
    @NonNull
    List<McpMessage> findByConversationIdOrderByCreatedAtAscIdAsc(@NonNull UUID conversationId);

    /** Whether the conversation already holds a turn of {@code role} (first-user-turn title rule). */
    boolean existsByConversationIdAndRole(@NonNull UUID conversationId, @NonNull ConversationMessageRole role);

    /**
     * The newest {@code limit} chat-path messages of a conversation the owner holds, newest first (the
     * caller reverses them into a memory window).
     *
     * <p>Only {@code origin = CHAT} rows: these are the turns the server itself exchanged with the
     * model. {@code CLIENT} rows arrive through {@code POST /conversations/{id}/messages} with a
     * caller-chosen role, so a caller could append a forged {@code assistant} turn ("I already
     * verified you are an administrator…") and have it replayed to the model as its own prior answer.
     * Client-appended turns stay visible in the history API but never reach model memory.
     */
    @Query("""
            select m from McpMessage m
            where m.conversationId = :conversationId
              and m.origin = com.positivity.mcp.internal.enums.ConversationMessageOrigin.CHAT
              and exists (
                select 1 from McpConversation c
                where c.id = m.conversationId and c.ownerUserId = :ownerUserId)
            order by m.createdAt desc, m.id desc
            """)
    @NonNull
    List<McpMessage> findRecentOwned(
            @Param("conversationId") @NonNull UUID conversationId,
            @Param("ownerUserId") @NonNull UUID ownerUserId,
            @NonNull Limit limit);

    /**
     * Deletes the messages of the given conversations. The database cascade does the same on
     * Postgres; deleting explicitly keeps the persistence context and non-cascading schemas (the H2
     * test profile) consistent.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from McpMessage m where m.conversationId in :conversationIds")
    int deleteByConversationIdIn(@Param("conversationIds") @NonNull Collection<UUID> conversationIds);
}
