package com.positivity.inventory.internal.repository;

import com.positivity.inventory.internal.entity.ExtProductReplica;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExtProductReplicaRepository extends JpaRepository<ExtProductReplica, UUID> {}
