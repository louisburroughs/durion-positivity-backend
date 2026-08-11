package com.positivity.supplier.internal.repository;

import com.positivity.supplier.internal.domain.model.SupplierCapability;
import com.positivity.supplier.internal.entity.ExchangeAuditEntity;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Exchange-audit persistence (ADR-0050 §7).
 *
 * <p>Every query filters on {@code vendorProfileId}, never {@code supplierRef}: the ref is a
 * renameable snapshot, so filtering by it would silently miss rows written before a rename.
 */
public interface ExchangeAuditRepository extends JpaRepository<ExchangeAuditEntity, UUID> {

    /** One supplier's exchanges in a window, newest first. Uses {@code idx_saudit_profile_started}. */
    @NonNull
    Page<ExchangeAuditEntity> findByVendorProfileIdAndStartedAtBetweenOrderByStartedAtDesc(
            @NonNull UUID vendorProfileId, @NonNull Instant from, @NonNull Instant to, @NonNull Pageable pageable);

    /** Narrowed by capability — how an operator actually investigates a specific integration. */
    @NonNull
    Page<ExchangeAuditEntity> findByVendorProfileIdAndCapabilityAndStartedAtBetweenOrderByStartedAtDesc(
            @NonNull UUID vendorProfileId,
            @NonNull SupplierCapability capability,
            @NonNull Instant from,
            @NonNull Instant to,
            @NonNull Pageable pageable);

    /** Every attempt of one logical call, for tracing a retry sequence. */
    @NonNull
    Page<ExchangeAuditEntity> findByCorrelationIdOrderByStartedAtAsc(
            @NonNull String correlationId, @NonNull Pageable pageable);

    /**
     * Nulls the payload columns of rows older than {@code cutoff}, keeping the metadata row
     * (ADR-0050 §7): the trail of what happened is retained permanently, only the commercial content
     * expires.
     *
     * <p>A bulk {@code @Modifying} update rather than load-mutate-save, deliberately: loading rows to
     * purge them would decrypt every payload just to discard it, which is both pointless work and a
     * needless widening of where plaintext exists. It also means the purge cannot fail on a row whose
     * key is no longer configured.
     *
     * @param cutoff rows started strictly before this are purged
     * @param purgedAt stamped so "purged" stays distinguishable from "never captured"
     * @return the number of rows whose payloads were nulled
     */
    @Modifying
    @Query("UPDATE ExchangeAuditEntity e SET e.requestPayload = NULL, e.responsePayload = NULL,"
            + " e.payloadsPurgedAt = :purgedAt"
            + " WHERE e.startedAt < :cutoff AND e.payloadsPurgedAt IS NULL"
            + " AND (e.requestPayload IS NOT NULL OR e.responsePayload IS NOT NULL)")
    int purgePayloadsOlderThan(@Param("cutoff") @NonNull Instant cutoff, @Param("purgedAt") @NonNull Instant purgedAt);

    /** How many rows still hold purgeable payloads; used to report purge progress. */
    @Query("SELECT COUNT(e) FROM ExchangeAuditEntity e WHERE e.startedAt < :cutoff"
            + " AND e.payloadsPurgedAt IS NULL"
            + " AND (e.requestPayload IS NOT NULL OR e.responsePayload IS NOT NULL)")
    long countPurgeableOlderThan(@Param("cutoff") @NonNull Instant cutoff);
}
