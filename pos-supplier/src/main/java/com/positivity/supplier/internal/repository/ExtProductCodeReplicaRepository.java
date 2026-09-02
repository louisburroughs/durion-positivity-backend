package com.positivity.supplier.internal.repository;

import com.positivity.supplier.internal.entity.ExtProductCodeReplica;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Local replica of pos-catalog product identity codes (ADR-0044 R3). */
public interface ExtProductCodeReplicaRepository extends JpaRepository<ExtProductCodeReplica, UUID> {

    /**
     * Products carrying an exact code under one scheme. Returns a list rather than an
     * {@link java.util.Optional} so that a transiently duplicated replica surfaces as a detectable
     * ambiguity instead of an arbitrary pick — pos-catalog's uniqueness constraint (#1232) means a
     * second row here is a replication defect, not a catalog state.
     */
    List<ExtProductCodeReplica> findByCodeTypeAndCode(String codeType, String code);

    /**
     * Products carrying an exact catalog SKU. A list for the same reason as
     * {@link #findByCodeTypeAndCode(String, String)}: SKU uniqueness is pos-catalog's invariant,
     * and a second row here is a replication defect that must surface as a detectable ambiguity
     * instead of an arbitrary pick.
     */
    List<ExtProductCodeReplica> findBySku(String sku);
}
