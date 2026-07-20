package com.positivity.tax.internal.repository;

import com.positivity.tax.common.enums.TaxProviderTransactionStatus;
import com.positivity.tax.internal.entity.TaxProviderTransaction;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
