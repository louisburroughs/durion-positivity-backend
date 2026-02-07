package com.positivity.catalog.internal.repository;

import com.positivity.catalog.internal.model.ProductEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface ProductRepository extends JpaRepository<ProductEntity, UUID> {
    List<ProductEntity> findByName(String name);
}
