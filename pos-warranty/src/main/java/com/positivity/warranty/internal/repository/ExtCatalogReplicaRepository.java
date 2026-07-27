package com.positivity.warranty.internal.repository;

import com.positivity.warranty.internal.entity.ExtCatalogReplica;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Read-only accessor for the {@code ext_catalog} replica (ADR-0044 §6, #924). */
public interface ExtCatalogReplicaRepository extends JpaRepository<ExtCatalogReplica, UUID> {}
