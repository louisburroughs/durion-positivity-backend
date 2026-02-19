package com.positivity.catalog.internal.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.positivity.catalog.internal.entity.ProductEntity;

import java.util.List;
import java.util.UUID;

public interface ProductRepository extends JpaRepository<ProductEntity, UUID> {
    List<ProductEntity> findByName(String name);

    boolean existsBySkuIgnoreCase(String sku);

    boolean existsByManufacturerIdAndManufacturerPartNumberIgnoreCase(UUID manufacturerId,
            String manufacturerPartNumber);

    boolean existsByManufacturerIdAndManufacturerPartNumberIgnoreCaseAndIdNot(
            UUID manufacturerId,
            String manufacturerPartNumber,
            UUID id);

    @Query("""
            SELECT p FROM ProductEntity p
            WHERE (:sku IS NULL OR LOWER(p.sku) = LOWER(:sku))
              AND (:mpn IS NULL OR LOWER(p.manufacturerPartNumber) = LOWER(:mpn))
              AND (
                :q IS NULL
                OR LOWER(p.name) LIKE LOWER(CONCAT('%', :q, '%'))
                OR LOWER(COALESCE(p.description, p.longDescription, '')) LIKE LOWER(CONCAT('%', :q, '%'))
              )
            """)
    Page<ProductEntity> searchProducts(
            @Param("q") String q,
            @Param("sku") String sku,
            @Param("mpn") String mpn,
            Pageable pageable);
}
