package com.positivity.vehicle.internal.repository;

import com.positivity.vehicle.internal.entity.VehicleCarePreference;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface VehicleCarePreferenceRepository extends JpaRepository<VehicleCarePreference, UUID> {

    Optional<VehicleCarePreference> findByVehicleId(@NonNull UUID vehicleId);

    boolean existsByVehicleId(@NonNull UUID vehicleId);
}
