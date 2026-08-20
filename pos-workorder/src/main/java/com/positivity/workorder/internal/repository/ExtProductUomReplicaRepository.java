package com.positivity.workorder.internal.repository;

import com.positivity.workorder.internal.entity.ExtProductUomReplica;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Read side of the catalog-owned {@code ext_product_uom} replica (ADR-0044 §6, #1413). */
public interface ExtProductUomReplicaRepository extends JpaRepository<ExtProductUomReplica, ExtProductUomReplica.Key> {

    /**
     * Declared decimal scales of the product's {@code BASE} rows, empty when the product has no
     * unit-of-measure rows at all — which is every product today, since nothing seeds
     * {@code product_uom} platform-wide.
     *
     * <p>Returns a list rather than a single value on purpose: the key allows one row per
     * {@code uomCode}, not one row per {@code uomType}, so a mis-seeded product could carry two
     * {@code BASE} rows and a scalar query would fail the whole part-entry path over a data defect
     * the caller can resolve on its own.
     */
    @NonNull
    @Query("SELECT u.precisionScale FROM ExtProductUomReplica u "
            + "WHERE u.productId = :productId AND u.uomType = 'BASE'")
    List<Integer> findBasePrecisionScales(@Param("productId") @NonNull UUID productId);

    /** Fact versions currently held for the product, empty when it has no rows. */
    @NonNull
    @Query("SELECT u.aggregateVersion FROM ExtProductUomReplica u WHERE u.productId = :productId")
    List<Long> findAggregateVersions(@Param("productId") @NonNull UUID productId);

    @Modifying
    @Query("DELETE FROM ExtProductUomReplica u WHERE u.productId = :productId")
    void deleteByProductId(@Param("productId") @NonNull UUID productId);
}
