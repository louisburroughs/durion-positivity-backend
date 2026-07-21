package com.positivity.tax.internal.service;

import com.positivity.tax.common.enums.TaxProviderTransactionStatus;
import com.positivity.tax.internal.entity.TaxProviderTransaction;
import com.positivity.tax.internal.repository.TaxProviderTransactionRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves the single {@code tax_provider_transaction} row for a source document, isolating the
 * find-or-insert in its OWN transaction (#983).
 *
 * <p>The lifecycle log has a {@code UNIQUE(reference_id)} index for commit idempotency. Two
 * concurrent commits for the same {@code referenceId} (e.g. a double-finalize, or a finalize
 * racing the scheduled re-commit job) both observe no row and both attempt an insert; the loser
 * hits the constraint. If that insert ran in the caller's transaction the violation would mark it
 * rollback-only, so the caller could no longer re-query the winning row within the same
 * transaction. Running the insert with {@link Propagation#REQUIRES_NEW} keeps the failure
 * contained: on conflict we re-read the row the other thread committed and return its id.
 *
 * <p>This is a separate bean (not a self-invoked method) because {@code REQUIRES_NEW} only takes
 * effect across a Spring proxy boundary.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class TaxProviderTransactionResolver {

    private final TaxProviderTransactionRepository repository;

    /**
     * Return the id of the lifecycle row for {@code referenceId}, creating it (status
     * {@link TaxProviderTransactionStatus#PENDING_COMMIT}) if absent. Concurrent-insert safe.
     *
     * @param referenceId   the source document id (commit idempotency key)
     * @param referenceType the source transaction type label (e.g. {@code INVOICE}); may be null
     * @param providerName  the selected provider label
     * @return the persisted row id
     */
    @NonNull
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UUID resolveId(@NonNull UUID referenceId, @Nullable String referenceType, @NonNull String providerName) {
        return repository
                .findByReferenceId(referenceId)
                .map(TaxProviderTransaction::getId)
                .orElseGet(() -> insertOrReread(referenceId, referenceType, providerName));
    }

    @NonNull
    private UUID insertOrReread(
            @NonNull UUID referenceId, @Nullable String referenceType, @NonNull String providerName) {
        try {
            TaxProviderTransaction row = TaxProviderTransaction.builder()
                    .referenceId(referenceId)
                    .referenceType(referenceType)
                    .provider(providerName)
                    .status(TaxProviderTransactionStatus.PENDING_COMMIT)
                    .attempts(0)
                    .build();
            // Flush now so a UNIQUE(reference_id) collision surfaces here, inside this
            // REQUIRES_NEW transaction, rather than later in the caller's.
            return repository.saveAndFlush(row).getId();
        } catch (DataIntegrityViolationException concurrentInsert) {
            // Another thread won the race — adopt its row.
            return repository
                    .findByReferenceId(referenceId)
                    .map(TaxProviderTransaction::getId)
                    .orElseThrow(() -> concurrentInsert);
        }
    }
}
