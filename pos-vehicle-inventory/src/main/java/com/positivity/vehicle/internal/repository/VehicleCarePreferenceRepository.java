package com.positivity.vehicle.internal.repository;

import com.positivity.vehicle.internal.entity.VehicleCarePreference;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VehicleCarePreferenceRepository extends JpaRepository<VehicleCarePreference, UUID> {

    Optional<VehicleCarePreference> findByVehicle_VehicleId(@NonNull UUID vehicleId);

    boolean existsByVehicle_VehicleId(@NonNull UUID vehicleId);
}
