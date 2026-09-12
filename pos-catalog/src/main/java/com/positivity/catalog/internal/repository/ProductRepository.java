package com.positivity.catalog.internal.repository;

import com.positivity.catalog.internal.entity.ProductCodeType;
import com.positivity.catalog.internal.entity.ProductEntity;
import com.positivity.catalog.internal.enums.TreadDesignSource;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductRepository extends JpaRepository<ProductEntity, UUID>, JpaSpecificationExecutor<ProductEntity> {
    List<ProductEntity> findByName(String name);

    boolean existsBySkuIgnoreCase(String sku);

    boolean existsByManufacturerIdAndManufacturerPartNumberIgnoreCase(
            UUID manufacturerId, String manufacturerPartNumber);

    boolean existsByManufacturerIdAndManufacturerPartNumberIgnoreCaseAndIdNot(
            UUID manufacturerId, String manufacturerPartNumber, UUID id);

    /**
     * The replay order: by id ascending, so a cursor resumes exactly where the previous page
     * stopped. Imposed by the search rather than taken from the caller, because it is what makes
     * {@code afterId} a cursor at all.
     */
    Sort BY_ID = Sort.by(Sort.Order.asc("id"));

    /**
     * A page of products for fact replay (#1309), ordered by id so a cursor can resume where the
     * previous page stopped.
     *
     * <p>Cursor rather than offset paging on purpose: a replay of a large catalog runs over several
     * requests, and offsets shift under concurrent product writes — a product created mid-replay
     * would silently displace another out of the window and leave a replica short of exactly the
     * fact the replay was meant to deliver.
     *
     * <p>The filter is a {@link FactReplaySearch} specification rather than a JPQL string of
     * {@code (:param IS NULL OR …)} clauses: see that class for why the string form returned 500
     * from PostgreSQL for every call while passing on H2 (issue #1891).
     *
     * @param afterId resume strictly after this product id, or null to start at the beginning
     * @param updatedSince only products changed at or after this instant, or null for every product
     * @param pageable supplies the page size only — the replay is positioned by {@code afterId}, not
     *     by an offset, and the order is always {@link #BY_ID}
     * @return one page of products, oldest id first
     */
    @NonNull
    default List<ProductEntity> findForReplay(
            @Nullable UUID afterId, @Nullable Instant updatedSince, @NonNull Pageable pageable) {
        return findBy(FactReplaySearch.<ProductEntity>matching(afterId, updatedSince), query -> {
            var sorted = query.sortBy(BY_ID);
            return (pageable.isUnpaged() ? sorted : sorted.limit(pageable.getPageSize())).all();
        });
    }

    /**
     * Exact match on a product code within its scheme (ADR-0053 §5). Returns a list rather than an
     * {@link java.util.Optional} so that dirty data predating the uniqueness constraint surfaces as
     * a detectable conflict instead of an arbitrary pick.
     */
    List<ProductEntity> findByProductCodeTypeAndProductCode(ProductCodeType productCodeType, String productCode);

    boolean existsByProductCodeTypeAndProductCode(ProductCodeType productCodeType, String productCode);

    boolean existsByProductCodeTypeAndProductCodeAndIdNot(ProductCodeType productCodeType, String productCode, UUID id);

    /**
     * Product-code values carried by more than one product, as {@code [codeType, code, count]}
     * rows. Mirrors the pre-constraint duplicate report produced by Flyway.
     */
    @Query("""
      SELECT p.productCodeType, p.productCode, COUNT(p)
      FROM ProductEntity p
      WHERE p.productCodeType IS NOT NULL AND p.productCode IS NOT NULL
      GROUP BY p.productCodeType, p.productCode
      HAVING COUNT(p) > 1
      ORDER BY p.productCodeType, p.productCode
      """)
    List<Object[]> findDuplicateProductCodeGroups();

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

    /**
     * Products currently attached to one tread design (#1645) — what an automatic re-match has to
     * look at before it revises anything, and what a resolve action reports back.
     */
    List<ProductEntity> findByTreadDesignId(UUID treadDesignId);

    /**
     * Whether any product attached to this design was attached by a person (#1645).
     *
     * <p>The re-match gate reads this and nothing else: one manual attachment is enough to make the
     * whole design's attachment set off-limits to the matcher, because a reviewer who connected one
     * size deliberately did not ask for the rest to be re-shuffled underneath it.
     */
    boolean existsByTreadDesignIdAndTreadDesignSource(UUID treadDesignId, TreadDesignSource treadDesignSource);
}
