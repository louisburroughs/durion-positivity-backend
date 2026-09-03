package com.positivity.shopmanager.internal.repository;

import com.positivity.shopmanager.internal.entity.ExtMobileUnitReplica;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ExtMobileUnitReplicaRepository extends JpaRepository<ExtMobileUnitReplica, UUID> {

    /**
     * Every active mobile unit based at one site, in display order — one query for the mobile half
     * of the dashboard's unit roster (#1658 AC1).
     *
     * <p>Same reasoning as {@link ExtBayReplicaRepository#findActiveByLocationOrdered}: a replica
     * name is legitimately null during replica lag and is not unique, and null placement differs
     * between PostgreSQL (last) and H2 (first), so the query pins named units first, then name,
     * then {@code mobileUnitId} — the primary key — to make the order total.
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
    @NonNull
    List<ExtMobileUnitReplica> findActiveByBaseLocationOrdered(@Param("baseLocationId") @NonNull UUID baseLocationId);
}
