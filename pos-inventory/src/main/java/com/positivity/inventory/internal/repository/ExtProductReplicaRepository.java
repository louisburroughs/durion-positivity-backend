package com.positivity.inventory.internal.repository;

import com.positivity.inventory.internal.entity.ExtProductReplica;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ExtProductReplicaRepository extends JpaRepository<ExtProductReplica, UUID> {

    /**
     * Products whose replicated category name, trimmed, is one of {@code categoryNames} (#1535).
     *
     * <p>Written as JPQL rather than a derived name on purpose: {@code ReplicaSkuCategoryLookup}
     * normalises the replicated name with {@code trimToNull} before matching, and a derived
     * {@code findByCategoryNameIn} would compare the raw column instead — quietly disagreeing with
     * the runtime path this query exists to predict.
     *
     * <p>This is a deliberate sequential scan. V41 indexed {@code category_id} and
     * {@code subcategory_id}, never {@code category_name}, and wrapping the column in
     * {@code trim()} would defeat a plain index anyway. That is acceptable here and only here:
     * this serves an operator-invoked audit and a once-per-boot advisory check, not a posting path.
     * Do not reuse it inside ledger posting or sourcing resolution.
     */
    @Query("select p from ExtProductReplica p where trim(p.categoryName) in :categoryNames")
    List<ExtProductReplica> findByTrimmedCategoryNameIn(@Param("categoryNames") Collection<String> categoryNames);
}
