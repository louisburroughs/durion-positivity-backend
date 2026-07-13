package com.positivity.inventory.internal.repository;

import com.positivity.inventory.internal.entity.ExtWorkorderPartReplica;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;

public interface ExtWorkorderPartReplicaRepository extends JpaRepository<ExtWorkorderPartReplica, UUID> {

    @Modifying
    void deleteByWorkorderId(UUID workorderId);
}
