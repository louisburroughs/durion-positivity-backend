package com.positivity.shopmanager.internal.repository;

import com.positivity.shopmanager.internal.entity.ExtLocationReplica;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Replica rows behind the location-scope check (ADR-0061 §2, #1872); written only by the consumer. */
public interface ExtLocationReplicaRepository extends JpaRepository<ExtLocationReplica, UUID> {}
