package com.positivity.nhtsa.internal.repository;

import com.positivity.nhtsa.internal.entity.Manufacturer;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ManufacturerRepository extends JpaRepository<Manufacturer, Long> {
}

