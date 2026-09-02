package com.positivity.workorder.internal.repository;

import com.positivity.workorder.internal.entity.ExtCatalogServiceReplica;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExtCatalogServiceReplicaRepository extends JpaRepository<ExtCatalogServiceReplica, UUID> {

    /** Batch read for the estimated-hours summation: one query per workorder, not per line. */
    @NonNull
    List<ExtCatalogServiceReplica> findByServiceIdIn(@NonNull Set<UUID> serviceIds);
}
