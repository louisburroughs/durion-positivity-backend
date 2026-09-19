package com.positivity.mcp.internal.repository;

import com.positivity.mcp.internal.entity.McpMessage;
import com.positivity.mcp.internal.enums.ConversationMessageOrigin;
import com.positivity.mcp.internal.enums.ConversationMessageRole;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Conversation messages (#2073), always read through their conversation and the bound tenant. */
public interface McpMessageRepository extends JpaRepository<McpMessage, UUID> {

    /** A conversation's messages, oldest first; the UUID v7 id breaks a same-instant tie in insert order. */
    List<McpMessage> findByConversationIdOrderByCreatedAtAscIdAsc(UUID conversationId);

    /** Whether the conversation already holds a turn of {@code role} (first-user-turn title rule). */
    boolean existsByConversationIdAndRole(UUID conversationId, ConversationMessageRole role);

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
    List<McpMessage> findRecentOwned(
            @Param("conversationId") UUID conversationId, @Param("ownerUserId") UUID ownerUserId, Limit limit);

    /**
     * Deletes the messages of the given conversations. The database cascade does the same on
     * Postgres; deleting explicitly keeps the persistence context and non-cascading schemas (the H2
     * test profile) consistent.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from McpMessage m where m.conversationId in :conversationIds")
    int deleteByConversationIdIn(@Param("conversationIds") Collection<UUID> conversationIds);

    /**
     * Sets (or, with all-null values, clears) the owner's rating of one message (#2075) in a single
     * statement: a repeat call replaces the rating atomically and concurrent calls are last-write-wins.
     * Matches only a message of {@code role} and {@code origin} in {@code conversationId} that {@code
     * ownerUserId} owns; the bound tenant scopes the rest (RLS). The chat path binds {@code ASSISTANT}
     * and {@code CHAT}: only answers the server itself produced can be rated (decision O4), so a
     * client-appended assistant turn matches nothing. Both are bound as parameters so the column
     * converters apply. A bulk update bypasses auditing, so {@code updatedAt} is
     * set explicitly. The conversation row is not touched (no history-rail reorder, no retention reset).
     *
     * @return the number of rows matched: 1, or 0 when the message is not reachable by this owner
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update McpMessage m
               set m.feedbackRating = :rating, m.feedbackReason = :reason, m.feedbackComment = :comment,
                   m.feedbackAt = :ratedAt, m.updatedAt = :now
             where m.id = :messageId
               and m.conversationId = :conversationId
               and m.role = :role
               and m.origin = :origin
               and exists (select 1 from McpConversation c
                            where c.id = m.conversationId and c.ownerUserId = :ownerUserId)
            """)
    int updateOwnedFeedback(
            @Param("conversationId") @NonNull UUID conversationId,
            @Param("messageId") @NonNull UUID messageId,
            @Param("ownerUserId") @NonNull UUID ownerUserId,
            @Param("role") @NonNull ConversationMessageRole role,
            @Param("origin") @NonNull ConversationMessageOrigin origin,
            @Param("rating") @Nullable String rating,
            @Param("reason") @Nullable String reason,
            @Param("comment") @Nullable String comment,
            @Param("ratedAt") @Nullable OffsetDateTime ratedAt,
            @Param("now") @NonNull OffsetDateTime now);
}
