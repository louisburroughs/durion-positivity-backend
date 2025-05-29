package com.positivity.nhtsa.repository;

import com.positivity.nhtsa.entity.Manufacturer;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ManufacturerRepository extends JpaRepository<Manufacturer, Long> {
}

