package com.positivity.customer.internal.repository;

import com.positivity.customer.internal.entity.VehicleProjection;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface VehicleProjectionRepository extends JpaRepository<VehicleProjection, UUID> {}
