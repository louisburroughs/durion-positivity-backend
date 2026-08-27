package com.positivity.inventory.internal.repository;

import com.positivity.inventory.internal.entity.ExtProductReplica;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ExtProductReplicaRepository extends JpaRepository<ExtProductReplica, UUID> {

    /**
     * Products whose replicated category name, trimmed, is one of {@code categoryNames} (#1535).
     *
     * <p>Written as JPQL rather than a derived name on purpose: the {@code SkuCategoryProvider} SPI
     * answers with {@code trimToNull(categoryName)} (see {@code ReplicaSkuCategoryLookup}), so a
     * derived {@code findByCategoryNameIn} would compare the raw column and quietly disagree with
     * the runtime path this query exists to predict.
     *
     * <p>This is a deliberate sequential scan — V41 indexed {@code category_id} and
     * {@code subcategory_id}, never {@code category_name}, and wrapping the column in {@code trim()}
     * would defeat a plain index anyway. That is why {@link Pageable} is <strong>required</strong>
     * rather than offered: the result is sized by however many products carry a configured category
     * name, which is operator-uncontrolled, so every caller must state a bound. There is
     * deliberately no unpaged overload.
     */
    @Query("select p from ExtProductReplica p where trim(p.categoryName) in :categoryNames")
    List<ExtProductReplica> findByTrimmedCategoryNameIn(
            @Param("categoryNames") Collection<String> categoryNames, Pageable pageable);

    /**
     * How many products the paged scan above would return unbounded, so a capped report can still
     * state a truthful population size (#1535).
     */
    @Query("select count(p) from ExtProductReplica p where trim(p.categoryName) in :categoryNames")
    long countByTrimmedCategoryNameIn(@Param("categoryNames") Collection<String> categoryNames);

    /**
     * Which of {@code categoryNames} any product actually carries (#1535). Answers "does this
     * configured category match anything at all" over the whole table, so the answer stays correct
     * when the row scan is capped — the capped page could omit a category's only products entirely.
     */
    @Query("select distinct trim(p.categoryName) from ExtProductReplica p where trim(p.categoryName) in :categoryNames")
    List<String> findDistinctTrimmedCategoryNamesIn(@Param("categoryNames") Collection<String> categoryNames);
}
