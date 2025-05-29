package com.positivity.vehiclefitment.repository;

import com.positivity.vehiclefitment.entity.Manufacturer;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ManufacturerRepository extends JpaRepository<Manufacturer, Long> {
}

