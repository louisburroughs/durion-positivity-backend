package com.positivity.shopmanager.internal.repository;

import com.positivity.shopmanager.internal.entity.Technician;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TechnicianRepository extends JpaRepository<Technician, UUID> {
    Optional<Technician> findByIdAndShopId(UUID id, UUID shopId);

    Optional<Technician> findFirstByShopIdAndPersonId(UUID shopId, UUID personId);
}
