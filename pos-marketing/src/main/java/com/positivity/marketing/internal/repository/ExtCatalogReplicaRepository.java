package com.positivity.marketing.internal.repository;

import com.positivity.marketing.internal.entity.ExtCatalogReplica;
import com.positivity.marketing.internal.enums.CatalogItemKind;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Read accessor for the {@code ext_catalog} replica (ADR-0044 §6, #1306).
 *
 * <p>Every finder returns a list because a reference may be written as a name, and names are not
 * unique in pos-catalog: two services can share one. The caller decides what a multi-row answer
 * means rather than having this interface guess with {@code Optional}.
 *
 * <p>The case-insensitive lookups are written as explicit {@code lower(...)} queries rather than
 * derived {@code IgnoreCase} finders, which Hibernate renders as {@code upper(...)}. The
 * difference matters: {@code V9} indexes these columns case-folded with {@code lower(...)}, the
 * repo's convention for case-insensitive lookup elsewhere, and a predicate folding the other way
 * could not use them.
 */
public interface ExtCatalogReplicaRepository extends JpaRepository<ExtCatalogReplica, UUID> {

    List<ExtCatalogReplica> findByItemKindAndCatalogItemId(CatalogItemKind itemKind, UUID catalogItemId);

    @Query("SELECT r FROM ExtCatalogReplica r WHERE r.itemKind = :itemKind AND lower(r.name) = lower(:name)")
    List<ExtCatalogReplica> findByKindAndNameIgnoringCase(
            @Param("itemKind") CatalogItemKind itemKind, @Param("name") String name);

    @Query("SELECT r FROM ExtCatalogReplica r WHERE lower(r.sku) = lower(:sku)")
    List<ExtCatalogReplica> findBySkuIgnoringCase(@Param("sku") String sku);

    List<ExtCatalogReplica> findByCategoryId(UUID categoryId);

    @Query("SELECT r FROM ExtCatalogReplica r WHERE lower(r.category) = lower(:category)")
    List<ExtCatalogReplica> findByCategoryNameIgnoringCase(@Param("category") String category);

    /**
     * How many rows of one kind the replica holds — the difference between "this module has never
     * heard of any service" and "this module knows the services and not that one".
     */
    long countByItemKind(CatalogItemKind itemKind);
}
