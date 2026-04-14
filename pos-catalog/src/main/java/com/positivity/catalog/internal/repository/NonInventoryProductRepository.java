package com.positivity.catalog.internal.repository;

import com.positivity.catalog.internal.entity.NonInventoryProductEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NonInventoryProductRepository extends JpaRepository<NonInventoryProductEntity, UUID> {
    List<NonInventoryProductEntity> findByName(String name);
}
