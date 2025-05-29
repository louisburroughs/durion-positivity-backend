package com.positivity.catalog.repository;

import com.positivity.catalog.model.CatalogEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public interface CatalogRepository extends JpaRepository<CatalogEntity, Long> {
    Optional<CatalogEntity> findByName(String name);
    void deleteByName(String name);
}

