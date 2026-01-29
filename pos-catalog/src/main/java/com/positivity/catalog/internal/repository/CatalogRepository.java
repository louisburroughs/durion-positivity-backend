package com.positivity.catalog.internal.repository;

import com.positivity.catalog.internal.model.CatalogEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CatalogRepository extends JpaRepository<CatalogEntity, Long> {
    List<CatalogEntity> findByNameContainingIgnoreCase(String name);
    void deleteByName(String name);
}

