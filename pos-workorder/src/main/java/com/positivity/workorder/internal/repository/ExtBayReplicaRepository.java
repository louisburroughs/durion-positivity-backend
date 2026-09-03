package com.positivity.workorder.internal.repository;

import com.positivity.workorder.internal.entity.ExtBayReplica;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ExtBayReplicaRepository extends JpaRepository<ExtBayReplica, UUID> {

    /**
     * Active bays at one site, in a <em>total</em> display order so the dispatch board's bay panel
     * is stable across refreshes.
     *
     * <p>The order is spelled out in the query rather than left to a derived
     * {@code OrderByNameAsc} method name, because name alone is not a total order and so cannot
     * deliver the stability the panel needs. Two bays may legitimately carry the same name, and a
     * replica row that arrived only as an assignment reference has a null name until the location
     * domain's event catches up — so ties are common, not theoretical, and a tie is resolved by
     * whatever order the database happened to return. Null placement is not portable either:
     * PostgreSQL sorts nulls last on ASC, H2 sorts them first. The tiers are therefore: named bays
     * before unnamed ones, then name, then {@code bayId} — the primary key, which makes the order
     * total and the panel jitter-free.
     *
     * @param locationId the site to scope to
     * @return the site's active bays, named first and then by name, ties broken by id
     */
    @Query("""
            select b from ExtBayReplica b
            where b.locationId = :locationId
              and b.active = true
            order by
              case when b.name is null then 1 else 0 end,
              b.name asc,
              b.bayId asc
            """)
    List<ExtBayReplica> findActiveByLocationOrdered(@Param("locationId") UUID locationId);
}
