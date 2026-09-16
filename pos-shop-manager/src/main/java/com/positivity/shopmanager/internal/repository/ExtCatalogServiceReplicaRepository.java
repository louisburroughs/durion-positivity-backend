package com.positivity.shopmanager.internal.repository;

import com.positivity.shopmanager.internal.entity.ExtCatalogServiceReplica;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

/** Catalog service replica rows (CAP-329); written only by the catalog-events consumer. */
public interface ExtCatalogServiceReplicaRepository extends JpaRepository<ExtCatalogServiceReplica, UUID> {

    @NonNull
    List<ExtCatalogServiceReplica> findAllByServiceIdIn(@NonNull Collection<UUID> serviceIds);
}
