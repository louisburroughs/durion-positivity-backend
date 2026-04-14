package com.positivity.catalog.internal.repository;

import com.positivity.catalog.internal.entity.ProductMsrpEntity;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductMsrpRepository extends JpaRepository<ProductMsrpEntity, UUID> {

    @Query("""
            select m from ProductMsrpEntity m
                                                where m.product.id = :productId
              and (m.effectiveEndDate is null or m.effectiveEndDate >= :startDate)
              and (:endDate is null or m.effectiveStartDate <= :endDate)
              and (:excludeMsrpId is null or m.msrpId <> :excludeMsrpId)
            """)
    List<ProductMsrpEntity> findOverlapping(
            @Param("productId") UUID productId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate,
            @Param("excludeMsrpId") UUID excludeMsrpId);

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

    List<ProductMsrpEntity> findByProduct_IdOrderByEffectiveStartDateDesc(UUID productId);

    long countByProduct_IdAndEffectiveEndDateIsNull(UUID productId);
}
