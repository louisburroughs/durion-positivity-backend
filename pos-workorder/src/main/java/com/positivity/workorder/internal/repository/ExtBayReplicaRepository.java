package com.positivity.workorder.internal.repository;

import com.positivity.workorder.internal.entity.ExtBayReplica;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExtBayReplicaRepository extends JpaRepository<ExtBayReplica, UUID> {

    /**
     * Active bays at one site, ordered by name so the dispatch board's bay panel is stable across
     * refreshes (a name-less replica row — one that arrived only as an assignment reference —
     * sorts last).
     *
     * @param locationId the site to scope to
     * @return the site's active bays
     */
    List<ExtBayReplica> findByLocationIdAndActiveTrueOrderByNameAsc(UUID locationId);
}
