package com.positivity.catalog.repository;

import com.positivity.catalog.model.ProductEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ProductRepository extends JpaRepository<ProductEntity, String> {
    List<ProductEntity> findByName(String name);
}
