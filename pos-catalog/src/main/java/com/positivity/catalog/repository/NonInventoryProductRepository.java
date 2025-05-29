package com.positivity.catalog.repository;

import com.positivity.catalog.model.NonInventoryProductEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface NonInventoryProductRepository extends JpaRepository<NonInventoryProductEntity, String> {
    List<NonInventoryProductEntity> findByName(String name);
}
