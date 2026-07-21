package com.positivity.tax.internal.repository;

import com.positivity.tax.common.enums.TaxProviderTransactionStatus;
import com.positivity.tax.internal.entity.TaxProviderTransaction;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for {@link TaxProviderTransaction} (story T6).
 */
public interface TaxProviderTransactionRepository extends JpaRepository<TaxProviderTransaction, UUID> {

    /**
     * Find the single lifecycle row for a source document (unique on reference_id).
     *
     * @param referenceId the document code / idempotency key
     * @return the row, if any
     */
    Optional<TaxProviderTransaction> findByReferenceId(UUID referenceId);

    /**
     * Rows in a given lifecycle status (used by the re-commit job for PENDING_COMMIT).
     *
     * @param status the status to select
     * @return matching rows
     */
    List<TaxProviderTransaction> findByStatus(TaxProviderTransactionStatus status);

    /**
     * Count rows in a given status (backing the PENDING_COMMIT backlog gauge).
     *
     * @param status the status to count
     * @return the row count
     */
    long countByStatus(TaxProviderTransactionStatus status);

    /**
     * Atomic find-or-create for the single lifecycle row of a source document. Inserts a fresh
     * {@code PENDING_COMMIT} row, or does nothing if a row already exists for {@code referenceId}
     * (the {@code UNIQUE(reference_id)} idempotency index). Using the storage-layer
     * {@code ON CONFLICT DO NOTHING} makes the concurrent-commit race safe WITHOUT throwing a
     * constraint violation, so the caller's transaction is never marked rollback-only and no
     * separate ({@code REQUIRES_NEW}) connection is needed. Runs on both Postgres 16 (prod) and
     * H2 in PostgreSQL mode (tests); the target-less {@code ON CONFLICT} form is portable across
     * both and, since the freshly generated UUID v7 {@code id} never collides, only the
     * {@code reference_id} unique index can trigger the conflict.
     *
     * @param id            freshly generated UUID v7 primary key (ignored on conflict)
     * @param referenceId   the source document id (commit idempotency key)
     * @param referenceType the source transaction type label (e.g. {@code INVOICE}); may be null
     * @param provider      the selected provider label
     * @param status        the initial lifecycle status label (e.g. {@code PENDING_COMMIT})
     * @param timestamp     creation/modification timestamp
     * @return the number of rows inserted (1 if this call won the race, 0 if a row already existed)
     */
    @Modifying
    @Query(value = """
                    INSERT INTO tax_provider_transaction
                        (id, reference_id, reference_type, provider, status, attempts, created_at, updated_at)
                    VALUES (:id, :referenceId, :referenceType, :provider, :status, 0, :timestamp, :timestamp)
                    ON CONFLICT DO NOTHING
                    """, nativeQuery = true)
    int insertIfAbsent(
            @Param("id") UUID id,
            @Param("referenceId") UUID referenceId,
            @Param("referenceType") String referenceType,
            @Param("provider") String provider,
            @Param("status") String status,
            @Param("timestamp") Instant timestamp);
}
