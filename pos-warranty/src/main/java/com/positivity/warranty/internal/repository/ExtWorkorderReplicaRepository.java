package com.positivity.warranty.internal.repository;

import com.positivity.warranty.internal.entity.ExtWorkorderReplica;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Read-only accessor for the {@code ext_workorder} replica (ADR-0044 §6, #924). */
public interface ExtWorkorderReplicaRepository extends JpaRepository<ExtWorkorderReplica, UUID> {

    List<ExtWorkorderReplica> findByCustomerId(UUID customerId);

    List<ExtWorkorderReplica> findByCustomerIdAndVehicleId(UUID customerId, UUID vehicleId);
}
