package com.positivity.vehiclefitment.internal.repository;

import com.positivity.vehiclefitment.internal.entity.Manufacturer;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ManufacturerRepository extends JpaRepository<Manufacturer, UUID> {
    List<Manufacturer> findAllByNameIgnoreCase(String name);
}
