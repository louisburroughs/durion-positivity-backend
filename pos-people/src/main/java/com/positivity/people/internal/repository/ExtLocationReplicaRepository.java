package com.positivity.people.internal.repository;

import com.positivity.people.internal.entity.ExtLocationReplica;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ExtLocationReplicaRepository extends JpaRepository<ExtLocationReplica, UUID> {

    /**
     * Active locations that sit at the top of the replicated parent-child hierarchy: a parent
     * of at least one edge but a child of none. Ordered by id (UUID v7 is time-ordered) so the
     * first row is a stable, deterministic pick. Mirrors pos-location's own hierarchy-root
     * query behind {@code GET /v1/locations/top-level}.
     *
     * Issue: #1636
     */
    @Query("""
            SELECT l FROM ExtLocationReplica l
            WHERE l.active = true
              AND EXISTS (SELECT 1 FROM ExtLocationParentReplica p WHERE p.parentId = l.locationId)
              AND NOT EXISTS (SELECT 1 FROM ExtLocationParentReplica c WHERE c.childId = l.locationId)
            ORDER BY l.locationId ASC
            """)
    List<ExtLocationReplica> findActiveHierarchyRoots();

    Optional<ExtLocationReplica> findFirstByActiveTrueOrderByLocationIdAsc();
}
