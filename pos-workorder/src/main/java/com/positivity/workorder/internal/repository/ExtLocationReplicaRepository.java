package com.positivity.workorder.internal.repository;

import com.positivity.workorder.internal.entity.ExtLocationReplica;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExtLocationReplicaRepository extends JpaRepository<ExtLocationReplica, UUID> {}
