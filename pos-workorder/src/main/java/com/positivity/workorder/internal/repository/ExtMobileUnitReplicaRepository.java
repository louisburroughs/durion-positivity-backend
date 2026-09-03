package com.positivity.workorder.internal.repository;

import com.positivity.workorder.internal.entity.ExtMobileUnitReplica;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ExtMobileUnitReplicaRepository extends JpaRepository<ExtMobileUnitReplica, UUID> {

    /**
     * Active mobile units based at one site, in a <em>total</em> display order so the dispatch
     * board's mobile-unit panel is stable across refreshes.
     *
     * <p>Same reasoning as {@link ExtBayReplicaRepository#findActiveByLocationOrdered}: name alone
     * is neither unique nor non-null on a replica row, and null placement differs between
     * PostgreSQL (last) and H2 (first), so the query pins named units first, then name, then
     * {@code mobileUnitId} — the primary key — to make the order total.
     *
     * @param baseLocationId the base site to scope to
     * @return the site's active mobile units, named first and then by name, ties broken by id
     */
    @Query("""
            select m from ExtMobileUnitReplica m
            where m.baseLocationId = :baseLocationId
              and m.active = true
            order by
              case when m.name is null then 1 else 0 end,
              m.name asc,
              m.mobileUnitId asc
            """)
    List<ExtMobileUnitReplica> findActiveByBaseLocationOrdered(@Param("baseLocationId") UUID baseLocationId);
}
