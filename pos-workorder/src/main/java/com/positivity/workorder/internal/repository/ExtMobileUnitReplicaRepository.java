package com.positivity.workorder.internal.repository;

import com.positivity.workorder.internal.entity.ExtMobileUnitReplica;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExtMobileUnitReplicaRepository extends JpaRepository<ExtMobileUnitReplica, UUID> {

    /**
     * Active mobile units based at one site, ordered by name so the dispatch board's mobile-unit
     * panel is stable across refreshes.
     *
     * @param baseLocationId the base site to scope to
     * @return the site's active mobile units
     */
    List<ExtMobileUnitReplica> findByBaseLocationIdAndActiveTrueOrderByNameAsc(UUID baseLocationId);
}
