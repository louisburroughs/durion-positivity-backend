package com.positivity.shopmanager.internal.repository;

import com.positivity.shopmanager.internal.entity.Technician;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface TechnicianRepository extends JpaRepository<Technician, UUID> {
    Optional<Technician> findByIdAndShopId(UUID id, UUID shopId);
}
