package com.positivity.catalog.internal.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.positivity.catalog.internal.entity.ServiceEntity;

import java.util.List;
import java.util.UUID;

public interface ServiceRepository extends JpaRepository<ServiceEntity, UUID> {
    List<ServiceEntity> findByName(String name);
}
