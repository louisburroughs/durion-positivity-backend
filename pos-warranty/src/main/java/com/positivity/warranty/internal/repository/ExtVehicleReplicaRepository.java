package com.positivity.warranty.internal.repository;

import com.positivity.warranty.internal.entity.ExtVehicleReplica;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Read-only accessor for the {@code ext_vehicle} replica (ADR-0044 §6, #924). */
public interface ExtVehicleReplicaRepository extends JpaRepository<ExtVehicleReplica, UUID> {}
