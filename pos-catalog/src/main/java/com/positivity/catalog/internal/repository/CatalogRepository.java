package com.positivity.catalog.internal.repository;

import com.positivity.catalog.internal.entity.CatalogEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CatalogRepository extends JpaRepository<CatalogEntity, UUID> {
    List<CatalogEntity> findByNameContainingIgnoreCase(String name);

    void deleteByName(String name);
}
