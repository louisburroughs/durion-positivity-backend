package com.positivity.catalog.internal.repository;

import com.positivity.catalog.internal.entity.ProductEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductRepository extends JpaRepository<ProductEntity, UUID> {
    List<ProductEntity> findByName(String name);

    boolean existsBySkuIgnoreCase(String sku);

    boolean existsByManufacturerIdAndManufacturerPartNumberIgnoreCase(
            UUID manufacturerId, String manufacturerPartNumber);

    boolean existsByManufacturerIdAndManufacturerPartNumberIgnoreCaseAndIdNot(
            UUID manufacturerId, String manufacturerPartNumber, UUID id);

    @Query("""
      SELECT p FROM ProductEntity p
      WHERE (:sku IS NULL OR LOWER(p.sku) = LOWER(CAST(:sku AS string)))
        AND (:mpn IS NULL OR LOWER(p.manufacturerPartNumber) = LOWER(CAST(:mpn AS string)))
        AND (
          :q IS NULL
          OR LOWER(p.name) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%'))
          OR LOWER(COALESCE(p.description, p.longDescription, '')) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%'))
        )
      """)
    Page<ProductEntity> searchProducts(
            @Param("q") String q, @Param("sku") String sku, @Param("mpn") String mpn, Pageable pageable);

    /**
     * Filtered search for the catalog search endpoint (CAP-247 Story #17).
     * Supports free-text query against name and description, exact SKU match
     * (SKU used as an exact AND filter), brand filter, and category filter.
     * No additional SKU ranking is applied at the service layer.
     */
    @Query("""
      SELECT p FROM ProductEntity p
      LEFT JOIN p.category c
      WHERE (:q IS NULL OR LOWER(p.name) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%'))
                       OR LOWER(COALESCE(p.description, p.longDescription, '')) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%')))
        AND (:sku IS NULL OR LOWER(p.sku) = LOWER(CAST(:sku AS string)))
        AND (:brand IS NULL OR LOWER(p.manufacturerBrand) = LOWER(CAST(:brand AS string)))
        AND (:category IS NULL OR LOWER(c.name) = LOWER(CAST(:category AS string)))
      """)
    Page<ProductEntity> searchProductsFiltered(
            @Param("q") String q,
            @Param("sku") String sku,
            @Param("brand") String brand,
            @Param("category") String category,
            Pageable pageable);
}
