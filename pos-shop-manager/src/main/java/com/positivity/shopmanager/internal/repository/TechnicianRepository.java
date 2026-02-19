package com.positivity.shopmanager.internal.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.positivity.shopmanager.internal.entity.Technician;

@Repository
public interface TechnicianRepository extends JpaRepository<Technician, UUID> {
    Optional<Technician> findByIdAndShopId(UUID id, UUID shopId);
}
