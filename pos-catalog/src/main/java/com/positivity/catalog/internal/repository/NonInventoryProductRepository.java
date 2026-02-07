package com.positivity.catalog.internal.repository;

import com.positivity.catalog.internal.model.NonInventoryProductEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface NonInventoryProductRepository extends JpaRepository<NonInventoryProductEntity, UUID> {
    List<NonInventoryProductEntity> findByName(String name);
}
