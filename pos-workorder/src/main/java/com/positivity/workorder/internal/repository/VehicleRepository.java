package com.positivity.workorder.internal.repository;

import com.positivity.workorder.internal.entity.Vehicle;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface VehicleRepository extends JpaRepository<Vehicle, UUID> {}
