package com.positivity.inventory.internal.repository;

import com.positivity.inventory.internal.entity.ExtStorageLocationReplica;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExtStorageLocationReplicaRepository extends JpaRepository<ExtStorageLocationReplica, UUID> {

    List<ExtStorageLocationReplica> findBySiteId(UUID siteId);
}
