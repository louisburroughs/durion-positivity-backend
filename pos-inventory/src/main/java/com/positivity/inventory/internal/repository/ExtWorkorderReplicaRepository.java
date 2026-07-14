package com.positivity.inventory.internal.repository;

import com.positivity.inventory.internal.entity.ExtWorkorderReplica;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExtWorkorderReplicaRepository extends JpaRepository<ExtWorkorderReplica, UUID> {}
