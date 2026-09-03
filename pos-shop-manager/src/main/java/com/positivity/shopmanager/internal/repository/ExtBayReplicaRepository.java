package com.positivity.shopmanager.internal.repository;

import com.positivity.shopmanager.internal.entity.ExtBayReplica;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExtBayReplicaRepository extends JpaRepository<ExtBayReplica, UUID> {

    /**
     * Every active bay at one site, in display order — one query for the whole bay half of the
     * dashboard's unit roster (#1658 AC1).
     */
    @NonNull
    List<ExtBayReplica> findByLocationIdAndActiveTrueOrderByNameAscBayIdAsc(@NonNull UUID locationId);
}
