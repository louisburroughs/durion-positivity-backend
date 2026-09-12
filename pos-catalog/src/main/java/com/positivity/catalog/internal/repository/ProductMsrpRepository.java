package com.positivity.catalog.internal.repository;

import com.positivity.catalog.internal.entity.ProductMsrpEntity;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductMsrpRepository
        extends JpaRepository<ProductMsrpEntity, UUID>, JpaSpecificationExecutor<ProductMsrpEntity> {

    /**
     * The MSRP records of one product whose effective window overlaps a candidate's — the check a
     * create or an update is admitted by.
     *
     * <p>The filter is a {@link ProductMsrpOverlapSearch} specification rather than a JPQL string of
     * {@code (:param IS NULL OR …)} clauses: see that class for why the string form made PostgreSQL
     * reject the statement — for a candidate carrying an {@code endDate}, and only for one (issue
     * #1891).
     *
     * @param productId the product whose MSRP history is being checked
     * @param startDate the candidate's inclusive start
     * @param endDate the candidate's inclusive end, or null for an open-ended candidate
     * @param excludeMsrpId the record being updated, so it cannot overlap itself, or null
     * @return the overlapping records, empty when the candidate is admissible
     */
    @NonNull
    default List<ProductMsrpEntity> findOverlapping(
            @NonNull UUID productId,
            @NonNull LocalDate startDate,
            @Nullable LocalDate endDate,
            @Nullable UUID excludeMsrpId) {
        return findAll(ProductMsrpOverlapSearch.matching(productId, startDate, endDate, excludeMsrpId));
    }

    @Query("""
            select m from ProductMsrpEntity m
                                                where m.product.id = :productId
              and m.effectiveStartDate <= :asOf
              and (m.effectiveEndDate is null or m.effectiveEndDate >= :asOf)
            order by m.effectiveStartDate desc, m.msrpId asc
            """)
    List<ProductMsrpEntity> findActiveCandidates(@Param("productId") UUID productId, @Param("asOf") LocalDate asOf);

    default Optional<ProductMsrpEntity> findActive(UUID productId, LocalDate asOf) {
        return findActiveCandidates(productId, asOf).stream().findFirst();
    }

    /**
     * Batch variant of {@link #findActiveCandidates} for enriched catalog search
     * (#828): returns every MSRP record active as of {@code asOf} across the given
     * products in a single query, ordered so the winning record per product (most
     * recently started, tie-broken by id) appears first. Callers pick the first
     * record encountered per product id to mirror {@link #findActive}.
     */
    @Query("""
            select m from ProductMsrpEntity m
            where m.product.id in :productIds
              and m.effectiveStartDate <= :asOf
              and (m.effectiveEndDate is null or m.effectiveEndDate >= :asOf)
            order by m.product.id asc, m.effectiveStartDate desc, m.msrpId asc
            """)
    List<ProductMsrpEntity> findActiveForProducts(
            @Param("productIds") Collection<UUID> productIds, @Param("asOf") LocalDate asOf);

    List<ProductMsrpEntity> findByProduct_IdOrderByEffectiveStartDateDesc(UUID productId);

    long countByProduct_IdAndEffectiveEndDateIsNull(UUID productId);
}
