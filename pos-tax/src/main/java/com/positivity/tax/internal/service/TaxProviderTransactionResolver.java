package com.positivity.tax.internal.service;

import com.positivity.shared.id.UUIDv7Generator;
import com.positivity.tax.common.enums.TaxProviderTransactionStatus;
import com.positivity.tax.internal.entity.TaxProviderTransaction;
import com.positivity.tax.internal.repository.TaxProviderTransactionRepository;
import com.positivity.tenancy.TenantResolver;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves the single {@code tax_provider_transaction} row for a source
 * document via an atomic
 * DB upsert (#983, #986).
 *
 * <p>
 * The lifecycle log has a {@code UNIQUE(reference_id)} index for commit
 * idempotency. Two
 * concurrent commits for the same {@code referenceId} (e.g. a double-finalize,
 * or a finalize
 * racing the scheduled re-commit job) both observe no row and both attempt an
 * insert; one loses
 * the race. Rather than let that loser surface as a Hibernate flush
 * {@code DataIntegrityViolationException} — which marks the transaction
 * rollback-only, so a
 * follow-up re-query within the SAME transaction can itself fail/leak — the
 * create is expressed
 * as {@code INSERT ... ON CONFLICT DO NOTHING} at the storage layer. The loser
 * silently inserts
 * zero rows; an unconditional {@code findByReferenceId} then returns the
 * winner's id.
 *
 * <p>
 * Propagation: this joins the caller's transaction (default {@code REQUIRED}).
 * The previous
 * implementation used {@code REQUIRES_NEW} to contain the constraint-violation
 * rollback in a
 * nested transaction, but that also forced a SECOND database connection to be
 * checked out while
 * the caller's connection stayed open holding locks on
 * {@code tax_provider_transaction} — a
 * self-deadlock (inner insert blocks on the suspended outer transaction's
 * locks) that only
 * cleared at the DB lock timeout and was the root cause of the multi-minute
 * pos-tax suite hang.
 * With the upsert there is no exception, no rollback-only, and no need for a
 * second connection,
 * so a single transaction on a single connection is both correct and
 * starvation-free.
 *
 * <p>
 * Kept as a separate bean purely for cohesion; it no longer relies on a proxy
 * boundary.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class TaxProviderTransactionResolver {

    private final TaxProviderTransactionRepository repository;
    private final Clock clock;
    private final TenantResolver tenantResolver;

    /**
     * Return the id of the lifecycle row for {@code referenceId}, creating it
     * (status
     * {@link TaxProviderTransactionStatus#PENDING_COMMIT}) if absent.
     * Concurrent-insert safe.
     *
     * @param referenceId   the source document id (commit idempotency key)
     * @param referenceType the source transaction type label (e.g.
     *                      {@code INVOICE}); may be null
     * @param providerName  the selected provider label
     * @return the persisted row id
     */
    @NonNull
    @Transactional
    public UUID resolveId(@NonNull UUID referenceId, @Nullable String referenceType, @NonNull String providerName) {
        return resolveId(referenceId, referenceType, providerName, TaxProviderTransactionStatus.PENDING_COMMIT);
    }

    /**
     * As {@link #resolveId(UUID, String, String)}, creating an absent row in {@code initialStatus}
     * (CAP:550 S32a: {@link TaxProviderTransactionStatus#ESTIMATED} when a plug-in prices a
     * committable document).
     *
     * @param referenceId   the source document id
     * @param referenceType the source transaction type label; may be null
     * @param providerName  the provider label
     * @param initialStatus the status of a newly created row
     * @return the persisted row id
     */
    @NonNull
    @Transactional
    public UUID resolveId(
            @NonNull UUID referenceId,
            @Nullable String referenceType,
            @NonNull String providerName,
            @NonNull TaxProviderTransactionStatus initialStatus) {
        return repository
                .findByReferenceId(referenceId)
                .map(TaxProviderTransaction::getId)
                .orElseGet(() -> upsert(referenceId, referenceType, providerName, initialStatus));
    }

    @NonNull
    private UUID upsert(
            @NonNull UUID referenceId,
            @Nullable String referenceType,
            @NonNull String providerName,
            @NonNull TaxProviderTransactionStatus initialStatus) {
        // Atomic create: inserts a fresh PENDING_COMMIT row, or no-ops on a concurrent
        // winner's
        // UNIQUE(reference_id) collision — never throws, never poisons this
        // transaction. The id
        // is generated in Java (ADR-0013 UUID v7); it is discarded on conflict.
        repository.insertIfAbsent(
                tenantResolver.require(),
                UUIDv7Generator.generate(),
                referenceId,
                referenceType,
                providerName,
                initialStatus.name(),
                Instant.now(clock));
        // Unconditional re-read returns the winning row id whether we inserted it or
        // adopted the
        // concurrent winner's.
        return repository
                .findByReferenceId(referenceId)
                .map(TaxProviderTransaction::getId)
                .orElseThrow(() -> new IllegalStateException(
                        "tax_provider_transaction row missing after upsert for reference " + referenceId));
    }
}
