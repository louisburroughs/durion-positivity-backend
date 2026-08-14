package com.positivity.inventory.internal.repository;

import com.positivity.inventory.internal.entity.ExtProductCodeReplica;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Local replica of pos-catalog product identity codes (CAP-322 #1312; ADR-0044 R3). */
public interface ExtProductCodeReplicaRepository extends JpaRepository<ExtProductCodeReplica, UUID> {

    /**
     * Products carrying an exact code under one scheme. Returns a list rather than an
     * {@link java.util.Optional} so that a transiently duplicated replica surfaces as a detectable
     * ambiguity instead of an arbitrary pick — pos-catalog constrains {@code (codeType, code)} at
     * the source (#1232), so a second row here is a replication defect, not a catalog state.
     */
    List<ExtProductCodeReplica> findByCodeTypeAndCode(String codeType, String code);
}
