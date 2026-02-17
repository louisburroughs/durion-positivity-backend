package com.positivity.catalog.internal.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.positivity.catalog.internal.entity.NonInventoryProductEntity;

import java.util.List;
import java.util.UUID;

public interface NonInventoryProductRepository extends JpaRepository<NonInventoryProductEntity, UUID> {
    List<NonInventoryProductEntity> findByName(String name);
}
