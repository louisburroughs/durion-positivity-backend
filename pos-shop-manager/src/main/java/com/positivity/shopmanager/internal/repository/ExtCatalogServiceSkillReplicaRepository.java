package com.positivity.shopmanager.internal.repository;

import com.positivity.shopmanager.internal.entity.ExtCatalogServiceSkillReplica;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

/** Replicated skill requirements of catalog services (CAP-329), replace-set by the consumer. */
public interface ExtCatalogServiceSkillReplicaRepository extends JpaRepository<ExtCatalogServiceSkillReplica, UUID> {

    @NonNull
    List<ExtCatalogServiceSkillReplica> findAllByServiceIdIn(@NonNull Collection<UUID> serviceIds);

    void deleteAllByServiceId(@NonNull UUID serviceId);
}
