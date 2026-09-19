package com.positivity.mcp.internal.repository;

import com.positivity.mcp.internal.entity.McpConversation;
import jakarta.persistence.LockModeType;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Conversations (#2073). Every query runs under the bound tenant (Hibernate {@code @TenantId} plus
 * row-level security); owner-scoped queries additionally filter on {@code ownerUserId}, so another
 * subject's id reads as absent.
 */
public interface McpConversationRepository extends JpaRepository<McpConversation, UUID> {

    /** The owner's history rail: pinned first, then most recently touched, id as the final tie-break. */
    @Query("""
            select c from McpConversation c
            where c.ownerUserId = :ownerUserId
            order by c.pinned desc, c.updatedAt desc, c.id desc
            """)
    @NonNull
    List<McpConversation> findRail(@Param("ownerUserId") @NonNull UUID ownerUserId, @NonNull Limit limit);

    Optional<McpConversation> findByIdAndOwnerUserId(@NonNull UUID id, @NonNull UUID ownerUserId);

    boolean existsByIdAndOwnerUserId(@NonNull UUID id, @NonNull UUID ownerUserId);

    /**
     * The owner's conversation, row-locked for the rest of the transaction so a concurrent turn,
     * delete or retention purge on the same conversation serializes behind this one.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from McpConversation c where c.id = :id and c.ownerUserId = :ownerUserId")
    Optional<McpConversation> findOwnedForUpdate(
            @Param("id") @NonNull UUID id, @Param("ownerUserId") @NonNull UUID ownerUserId);

    /**
     * Every conversation the owner holds, row-locked (clear-all). Locks in the same order as {@link
     * #findPurgeableForUpdate} ({@code updatedAt}, then id) so a clear-all and a concurrent purge
     * cannot deadlock on each other's rows.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select c from McpConversation c
            where c.ownerUserId = :ownerUserId
            order by c.updatedAt asc, c.id asc
            """)
    @NonNull
    List<McpConversation> findAllOwnedForUpdate(@Param("ownerUserId") @NonNull UUID ownerUserId);

    /**
     * Up to {@code limit} unpinned conversations idle since before {@code cutoff}, oldest first,
     * row-locked. A turn that touched a row first keeps it: once the lock is granted the row is
     * re-checked against the predicate. Bounded so one purge transaction never locks or loads an
     * unbounded backlog; the caller repeats until a batch comes back short.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select c from McpConversation c
            where c.pinned = false and c.updatedAt < :cutoff
            order by c.updatedAt asc, c.id asc
            """)
    @NonNull
    List<McpConversation> findPurgeableForUpdate(@Param("cutoff") @NonNull OffsetDateTime cutoff, @NonNull Limit limit);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from McpConversation c where c.id in :ids")
    int deleteByIdIn(@Param("ids") @NonNull Collection<UUID> ids);
}
