package com.positivity.shopmanager.internal.repository;

import com.positivity.shopmanager.internal.entity.ExtMobileUnitReplica;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExtMobileUnitReplicaRepository extends JpaRepository<ExtMobileUnitReplica, UUID> {

    /**
     * Every active mobile unit based at one site, in display order — one query for the mobile half
     * of the dashboard's unit roster (#1658 AC1).
     */
    @NonNull
    List<ExtMobileUnitReplica> findByBaseLocationIdAndActiveTrueOrderByNameAscMobileUnitIdAsc(
            @NonNull UUID baseLocationId);
}
