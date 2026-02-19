package com.positivity.vehiclefitment.internal.repository;

import com.positivity.vehiclefitment.internal.entity.Make;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface MakeRepository extends JpaRepository<Make, UUID> {
    List<Make> findByManufacturerId(UUID manufacturerId);
}

