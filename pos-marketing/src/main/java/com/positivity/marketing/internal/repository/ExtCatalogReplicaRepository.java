package com.positivity.marketing.internal.repository;

import com.positivity.marketing.internal.entity.ExtCatalogReplica;
import com.positivity.marketing.internal.enums.CatalogItemKind;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Read accessor for the {@code ext_catalog} replica (ADR-0044 §6, #1306).
 *
 * <p>Every finder returns a list because a reference may be written as a name, and names are not
 * unique in pos-catalog: two services can share one. The caller decides what a multi-row answer
 * means rather than having this interface guess with {@code Optional}.
 */
public interface ExtCatalogReplicaRepository extends JpaRepository<ExtCatalogReplica, UUID> {

    List<ExtCatalogReplica> findByItemKindAndCatalogItemId(CatalogItemKind itemKind, UUID catalogItemId);

    List<ExtCatalogReplica> findByItemKindAndNameIgnoreCase(CatalogItemKind itemKind, String name);

    List<ExtCatalogReplica> findBySkuIgnoreCase(String sku);

    List<ExtCatalogReplica> findByCategoryId(UUID categoryId);

    List<ExtCatalogReplica> findByCategoryIgnoreCase(String category);

    /**
     * How many rows of one kind the replica holds — the difference between "this module has never
     * heard of any service" and "this module knows the services and not that one".
     */
    long countByItemKind(CatalogItemKind itemKind);
}
