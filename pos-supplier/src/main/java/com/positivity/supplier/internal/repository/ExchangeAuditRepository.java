package com.positivity.supplier.internal.repository;

import com.positivity.supplier.internal.domain.model.SupplierCapability;
import com.positivity.supplier.internal.entity.ExchangeAuditEntity;
import java.time.Instant;
import java.util.Optional;
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
 *
 * <h2>Read queries return {@link ExchangeAuditMetadata}, never the entity</h2>
 *
 * The payload columns are encrypted and their converter runs at hydration time, so returning
 * {@link ExchangeAuditEntity} from a listing would decrypt every payload on the page just to throw it
 * away, and one unreadable row would fail the entire listing. The constructor expressions below name
 * the selected columns explicitly, which makes "no plaintext is produced here" true by inspection
 * rather than by trusting a projection optimisation. See {@link ExchangeAuditMetadata} for the full
 * reasoning.
 */
public interface ExchangeAuditRepository extends JpaRepository<ExchangeAuditEntity, UUID> {

    /**
     * The metadata {@code SELECT} clause, shared so the column list cannot drift between queries.
     *
     * <p>Payload presence is computed in SQL with a null test rather than by reading the columns: the
     * caller learns whether content exists without any ciphertext leaving the database.
     */
    String SELECT_METADATA = "SELECT new com.positivity.supplier.internal.repository.ExchangeAuditMetadata("
            + " e.exchangeAuditId, e.vendorProfileId, e.supplierRef, e.bindingId, e.capability,"
            + " e.protocolFamily, e.protocolVersion, e.httpMethod, e.endpointUri, e.attempt,"
            + " e.correlationId, e.outcome, e.httpStatus, e.startedAt, e.durationMs, e.failureDetail,"
            + " e.captureLevel,"
            + " CASE WHEN e.requestPayload IS NOT NULL THEN TRUE ELSE FALSE END,"
            + " CASE WHEN e.responsePayload IS NOT NULL THEN TRUE ELSE FALSE END,"
            + " e.payloadsPurgedAt, e.createdBy)"
            + " FROM ExchangeAuditEntity e";

    /** One supplier's exchanges in a window, newest first. Uses {@code idx_saudit_profile_started}. */
    @Query(
            value = SELECT_METADATA + " WHERE e.vendorProfileId = :vendorProfileId"
                    + " AND e.startedAt >= :from AND e.startedAt < :to"
                    + " ORDER BY e.startedAt DESC",
            countQuery = "SELECT COUNT(e) FROM ExchangeAuditEntity e WHERE e.vendorProfileId = :vendorProfileId"
                    + " AND e.startedAt >= :from AND e.startedAt < :to")
    @NonNull
    Page<ExchangeAuditMetadata> findMetadataByProfileAndWindow(
            @Param("vendorProfileId") @NonNull UUID vendorProfileId,
            @Param("from") @NonNull Instant from,
            @Param("to") @NonNull Instant to,
            @NonNull Pageable pageable);

    /** Narrowed by capability — how an operator actually investigates a specific integration. */
    @Query(
            value = SELECT_METADATA + " WHERE e.vendorProfileId = :vendorProfileId"
                    + " AND e.capability = :capability"
                    + " AND e.startedAt >= :from AND e.startedAt < :to"
                    + " ORDER BY e.startedAt DESC",
            countQuery = "SELECT COUNT(e) FROM ExchangeAuditEntity e WHERE e.vendorProfileId = :vendorProfileId"
                    + " AND e.capability = :capability AND e.startedAt >= :from AND e.startedAt < :to")
    @NonNull
    Page<ExchangeAuditMetadata> findMetadataByProfileCapabilityAndWindow(
            @Param("vendorProfileId") @NonNull UUID vendorProfileId,
            @Param("capability") @NonNull SupplierCapability capability,
            @Param("from") @NonNull Instant from,
            @Param("to") @NonNull Instant to,
            @NonNull Pageable pageable);

    /**
     * Every attempt of one logical call, for tracing a retry sequence.
     *
     * <p>Ascending, unlike the window listings: a retry sequence only reads as a sequence in the order
     * it happened.
     */
    @Query(
            value = SELECT_METADATA + " WHERE e.correlationId = :correlationId ORDER BY e.startedAt ASC",
            countQuery = "SELECT COUNT(e) FROM ExchangeAuditEntity e WHERE e.correlationId = :correlationId")
    @NonNull
    Page<ExchangeAuditMetadata> findMetadataByCorrelationId(
            @Param("correlationId") @NonNull String correlationId, @NonNull Pageable pageable);

    /** One row's metadata. The payload path loads this first, so an access can be recorded whatever
     * the payload turns out to be. */
    @Query(SELECT_METADATA + " WHERE e.exchangeAuditId = :exchangeAuditId")
    @NonNull
    Optional<ExchangeAuditMetadata> findMetadataById(@Param("exchangeAuditId") @NonNull UUID exchangeAuditId);

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
