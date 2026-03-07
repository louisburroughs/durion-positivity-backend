package com.positivity.customer.internal.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.positivity.customer.internal.entity.VehicleProjection;

@Repository
public interface VehicleProjectionRepository extends JpaRepository<VehicleProjection, UUID> {
}
