package com.positivity.catalog.internal.repository;

import com.positivity.catalog.internal.model.NonInventoryProductEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface NonInventoryProductRepository extends JpaRepository<NonInventoryProductEntity, Long> { // Changed String to Long
    List<NonInventoryProductEntity> findByName(String name);
}
