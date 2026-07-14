package com.positivity.invoice.internal.repository;

import com.positivity.invoice.internal.entity.ExtLocationReplica;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExtLocationReplicaRepository extends JpaRepository<ExtLocationReplica, UUID> {}
