package com.positivity.customer.internal.repository;

import com.positivity.customer.internal.entity.PartyTagAssignment;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PartyTagAssignmentRepository extends JpaRepository<PartyTagAssignment, UUID> {

    List<PartyTagAssignment> findByPartyId(UUID partyId);

    List<PartyTagAssignment> findByTagId(UUID tagId);

    /**
     * Batch-load for segment resolution, which tests tag membership across a
     * candidate set.
     */
    List<PartyTagAssignment> findByPartyIdIn(java.util.Collection<UUID> partyIds);

    Optional<PartyTagAssignment> findByPartyIdAndTagId(UUID partyId, UUID tagId);

    long countByTagId(UUID tagId);

    void deleteByTagId(UUID tagId);

    /**
     * Party ids carrying every one of the given tags — the AND semantics segment
     * predicates need.
     */
    @Query("""
            SELECT a.partyId FROM PartyTagAssignment a
            WHERE a.tagId IN :tagIds
            GROUP BY a.partyId
            HAVING COUNT(DISTINCT a.tagId) = :tagCount
            """)
    List<UUID> findPartyIdsHavingAllTags(@Param("tagIds") List<UUID> tagIds, @Param("tagCount") long tagCount);

    /** Party ids carrying at least one of the given tags. */
    @Query("SELECT DISTINCT a.partyId FROM PartyTagAssignment a WHERE a.tagId IN :tagIds")
    List<UUID> findPartyIdsHavingAnyTag(@Param("tagIds") List<UUID> tagIds);
}
