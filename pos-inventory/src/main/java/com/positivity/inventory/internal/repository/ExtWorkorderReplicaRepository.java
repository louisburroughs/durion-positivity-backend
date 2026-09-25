package com.positivity.inventory.internal.repository;

import com.positivity.inventory.internal.entity.ExtWorkorderReplica;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ExtWorkorderReplicaRepository extends JpaRepository<ExtWorkorderReplica, UUID> {

    /**
     * Cross-dock-eligible workorders (#2211): status not COMPLETED/CANCELLED/CLOSED (matching
     * {@code WorkorderValidationService#isClosedWorkorderStatus}) and at least one part line,
     * matching {@code likeQuery} against {@code workorderNumber} (case-insensitive contains) or
     * {@code queryId} against the workorder id exactly. Both null selects every eligible
     * workorder. Most-recently-updated first; the caller caps the page size.
     */
    @Query("""
            select w from ExtWorkorderReplica w
            where upper(trim(coalesce(w.status, ''))) not in ('COMPLETED', 'CANCELLED', 'CLOSED')
              and exists (select 1 from ExtWorkorderPartReplica p where p.workorderId = w.workorderId)
              and (:likeQuery is null
                   or lower(w.workorderNumber) like :likeQuery
                   or (:queryId is not null and w.workorderId = :queryId))
            order by w.updatedAt desc
            """)
    List<ExtWorkorderReplica> searchEligibleForCrossDock(
            @Param("likeQuery") String likeQuery, @Param("queryId") UUID queryId, Pageable pageable);
}
