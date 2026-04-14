package com.positivity.vehiclefitment.internal.repository;

import com.positivity.vehiclefitment.internal.entity.Manufacturer;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ManufacturerRepository extends JpaRepository<Manufacturer, UUID> {}
