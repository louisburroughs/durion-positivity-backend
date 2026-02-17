package com.positivity.catalog.internal.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.positivity.catalog.internal.entity.ProductEntity;

import java.util.List;
import java.util.UUID;

public interface ProductRepository extends JpaRepository<ProductEntity, UUID> {
    List<ProductEntity> findByName(String name);
}
