package com.positivity.workorder.internal.repository;

import com.positivity.workorder.internal.entity.ExtVehicleReplica;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Replicated vehicle identity, for naming a vehicle to a fleet program (#1346). */
@Repository
public interface ExtVehicleReplicaRepository extends JpaRepository<ExtVehicleReplica, UUID> {}
