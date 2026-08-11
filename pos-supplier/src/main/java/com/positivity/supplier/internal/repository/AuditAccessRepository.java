package com.positivity.supplier.internal.repository;

import com.positivity.supplier.internal.entity.AuditAccessEntity;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** Access-audit persistence (ADR-0050 §7) — who read which exchange payload, when. */
public interface AuditAccessRepository extends JpaRepository<AuditAccessEntity, UUID> {

    /**
     * Who read a given exchange, newest first — the question an investigation actually asks.
     *
     * <p>Paginated rather than a plain list: an exchange that draws attention draws repeated reads, and
     * an unbounded list would be the one query that degrades exactly when it is being used in anger.
     */
    @NonNull
    Page<AuditAccessEntity> findByExchangeAuditIdOrderByAccessedAtDesc(
            @NonNull UUID exchangeAuditId, @NonNull Pageable pageable);
}
