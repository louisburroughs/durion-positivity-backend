package com.positivity.catalog.internal.repository;

import com.positivity.catalog.internal.entity.ExtLocationReplica;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Read-only location replica carrying the location-scope ancestor sets (ADR-0061 §2, #1885). */
public interface ExtLocationReplicaRepository extends JpaRepository<ExtLocationReplica, UUID> {}
