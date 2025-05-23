package com.positivity.poscatalog.repository;

import com.positivity.poscatalog.model.NonInventoryProductEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface NonInventoryProductRepository extends JpaRepository<NonInventoryProductEntity, String> {
    List<NonInventoryProductEntity> findByName(String name);
}
