package com.positivity.vehiclefitment.internal.repository;

import com.positivity.vehiclefitment.internal.entity.Make;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MakeRepository extends JpaRepository<Make, UUID> {
    List<Make> findByManufacturerId(UUID manufacturerId);

    Optional<Make> findByManufacturerIdAndNameIgnoreCase(UUID manufacturerId, String name);

    List<Make> findAllByNameIgnoreCase(String name);
}
