package com.positivity.shopmanager.internal.repository;

import com.positivity.shopmanager.internal.entity.ExtBayReplica;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ExtBayReplicaRepository extends JpaRepository<ExtBayReplica, UUID> {

    /**
     * Every active bay at one site, in display order — one query for the whole bay half of the
     * dashboard's unit roster (#1658 AC1).
     *
     * <p>The order is spelled out in the query rather than left to a derived
     * {@code OrderByNameAscBayIdAsc} method name because the derived form cannot say where nulls
     * go, and {@code name} is legitimately null while the location domain's event is still in
     * flight. Null placement is not portable — PostgreSQL sorts nulls last on ASC, H2 sorts them
     * first — so the same replica state would render two different rosters depending on the
     * database. The tiers are: named bays before unnamed ones, then name, then {@code bayId} — the
     * primary key, which makes the order total and the roster stable across refreshes.
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
    @NonNull
    List<ExtBayReplica> findActiveByLocationOrdered(@Param("locationId") @NonNull UUID locationId);
}
