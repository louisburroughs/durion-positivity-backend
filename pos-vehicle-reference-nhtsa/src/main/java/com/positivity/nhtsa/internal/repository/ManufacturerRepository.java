package com.positivity.nhtsa.internal.repository;

import com.positivity.nhtsa.internal.entity.Manufacturer;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ManufacturerRepository extends JpaRepository<Manufacturer, UUID> {
}
