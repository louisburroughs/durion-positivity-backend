package com.positivity.shopManager.repository;

import com.positivity.shopManager.entity.Technician;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface TechnicianRepository extends JpaRepository<Technician, Long> {
    Optional<Technician> findByIdAndShopId(Long id, Long shopId);
}
