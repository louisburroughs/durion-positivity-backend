package com.positivity.tax.internal.service;

import com.positivity.tax.common.dto.TaxProviderTransactionResult;
import com.positivity.tax.common.enums.TaxProviderTransactionStatus;
import com.positivity.tax.internal.entity.TaxProviderTransaction;
import com.positivity.tax.internal.repository.TaxProviderTransactionRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the provider tax-document lifecycle log (story T6, decision D-T3).
 * <p>
 * Persistence is centralized here (rather than inside each provider) so idempotency,
 * PENDING_COMMIT recording and the scheduled re-commit job share a single writer; the
 * providers stay pure. Commit follows estimate-and-true-up: a provider failure never
 * propagates — it records a {@link TaxProviderTransactionStatus#PENDING_COMMIT} row for the
 * re-commit job. The PENDING_COMMIT backlog is exposed as a Micrometer gauge so true-up lag
 * is visible.
 * <p>
 * #985: a PENDING_COMMIT row presupposes the provider document already EXISTS under
 * {@code code == referenceId} — {@code commit()} only promotes it. That precondition is
 * guaranteed by the caller: invoice finalization first runs a {@code committable=true}
 * calculation (which persists an uncommitted {@code SalesInvoice} document, and whose
 * failure blocks finalization) before invoking commit. The re-commit job therefore always
 * retries against an existing document and converges once the provider recovers.
 */
@Slf4j
@Service
public class TaxProviderLifecycleService {

    /** Max stored length of {@code last_error} (matches the column width). */
    private static final int MAX_ERROR_LEN = 1024;

    private final TaxProviderSelector selector;
    private final TaxProviderTransactionRepository repository;
    private final TaxProviderTransactionResolver resolver;

    public TaxProviderLifecycleService(
            TaxProviderSelector selector,
            TaxProviderTransactionRepository repository,
            TaxProviderTransactionResolver resolver,
            ObjectProvider<MeterRegistry> meterRegistry) {
        this.selector = selector;
        this.repository = repository;
        this.resolver = resolver;
        MeterRegistry registry = meterRegistry.getIfAvailable();
        if (registry == null) {
            log.warn("No MeterRegistry available; tax provider PENDING_COMMIT backlog gauge not registered");
        } else {
            Gauge.builder(
                            "tax.provider.pending_commit.backlog",
                            this,
                            TaxProviderLifecycleService::pendingCommitBacklog)
                    .description("Count of tax provider documents awaiting re-commit (estimate-and-true-up lag)")
                    .register(registry);
        }
    }

    /**
     * Commit the document for {@code referenceId} at invoice finalization.
     * <p>
     * Idempotent: an already-COMMITTED document is a no-op. On provider failure this does
     * NOT throw — it records a PENDING_COMMIT row so the sale is never blocked (D-T3).
     *
     * @param referenceId   the document code (source invoice id)
     * @param referenceType the source transaction type label (e.g. {@code INVOICE})
     * @return the recorded outcome
     */
    @NonNull
    @Transactional
    public TaxProviderTransactionResult commit(@NonNull UUID referenceId, @Nullable String referenceType) {
        TaxProviderClient provider = selector.select();
        // Isolate the find-or-insert so a concurrent-commit UNIQUE(reference_id) collision can
        // never poison this transaction (#983).
        UUID rowId = resolver.resolveId(referenceId, referenceType, provider.providerName());
        TaxProviderTransaction tx = repository.findById(rowId).orElseThrow();

        if (tx.getStatus() == TaxProviderTransactionStatus.COMMITTED) {
            return new TaxProviderTransactionResult(
                    referenceId,
                    TaxProviderTransactionStatus.COMMITTED,
                    tx.getExternalTransactionId(),
                    "already committed");
        }

        try {
            TaxProviderTransactionResult result = provider.commit(referenceId);
            tx.setStatus(TaxProviderTransactionStatus.COMMITTED);
            tx.setExternalTransactionId(result.externalTransactionId());
            tx.setLastError(null);
            tx.setAttempts(tx.getAttempts() + 1);
            repository.save(tx);
            return new TaxProviderTransactionResult(
                    referenceId, TaxProviderTransactionStatus.COMMITTED, tx.getExternalTransactionId(), "committed");
        } catch (RuntimeException ex) {
            // D-T3 estimate-and-true-up: never block the sale — record PENDING_COMMIT.
            tx.setStatus(TaxProviderTransactionStatus.PENDING_COMMIT);
            tx.setLastError(truncate(ex.getMessage()));
            tx.setAttempts(tx.getAttempts() + 1);
            repository.save(tx);
            log.warn(
                    "Provider commit failed for {}; recorded PENDING_COMMIT for re-commit job: {}",
                    referenceId,
                    ex.getMessage());
            return new TaxProviderTransactionResult(
                    referenceId, TaxProviderTransactionStatus.PENDING_COMMIT, null, "pending commit");
        }
    }

    /**
     * Void the document for {@code referenceId} at invoice revert-to-DRAFT.
     *
     * @param referenceId the document code (source invoice id)
     * @return the recorded outcome
     */
    @NonNull
    @Transactional
    public TaxProviderTransactionResult voidTransaction(@NonNull UUID referenceId) {
        TaxProviderClient provider = selector.select();
        UUID rowId = resolver.resolveId(referenceId, null, provider.providerName());
        TaxProviderTransaction tx = repository.findById(rowId).orElseThrow();
        try {
            TaxProviderTransactionResult result = provider.voidTransaction(referenceId);
            tx.setStatus(TaxProviderTransactionStatus.VOIDED);
            if (result.externalTransactionId() != null) {
                tx.setExternalTransactionId(result.externalTransactionId());
            }
            tx.setLastError(null);
            tx.setAttempts(tx.getAttempts() + 1);
            repository.save(tx);
            return new TaxProviderTransactionResult(
                    referenceId, TaxProviderTransactionStatus.VOIDED, tx.getExternalTransactionId(), "voided");
        } catch (RuntimeException ex) {
            tx.setStatus(TaxProviderTransactionStatus.FAILED);
            tx.setLastError(truncate(ex.getMessage()));
            tx.setAttempts(tx.getAttempts() + 1);
            repository.save(tx);
            log.warn("Provider void failed for {}: {}", referenceId, ex.getMessage());
            return new TaxProviderTransactionResult(
                    referenceId, TaxProviderTransactionStatus.FAILED, null, "void failed");
        }
    }

    /**
     * Scheduled re-commit job (D-T3): re-attempt commit for every PENDING_COMMIT row and
     * promote it to COMMITTED when the provider recovers. Fixed-delay MVP.
     */
    @Scheduled(fixedDelayString = "${tax.provider.recommit.fixed-delay-ms:60000}")
    @Transactional
    public void recommitPending() {
        List<TaxProviderTransaction> pending = repository.findByStatus(TaxProviderTransactionStatus.PENDING_COMMIT);
        if (pending.isEmpty()) {
            return;
        }
        TaxProviderClient provider = selector.select();
        int promoted = 0;
        for (TaxProviderTransaction tx : pending) {
            try {
                TaxProviderTransactionResult result = provider.commit(tx.getReferenceId());
                tx.setStatus(TaxProviderTransactionStatus.COMMITTED);
                tx.setExternalTransactionId(result.externalTransactionId());
                tx.setLastError(null);
                tx.setAttempts(tx.getAttempts() + 1);
                repository.save(tx);
                promoted++;
            } catch (RuntimeException ex) {
                tx.setAttempts(tx.getAttempts() + 1);
                tx.setLastError(truncate(ex.getMessage()));
                repository.save(tx);
                log.warn("Re-commit still failing for {}: {}", tx.getReferenceId(), ex.getMessage());
            }
        }
        if (promoted > 0) {
            log.info("Re-commit job promoted {} of {} PENDING_COMMIT tax documents", promoted, pending.size());
        }
    }

    /**
     * Current PENDING_COMMIT backlog (Micrometer gauge value).
     *
     * @return the number of documents awaiting re-commit
     */
    public double pendingCommitBacklog() {
        return repository.countByStatus(TaxProviderTransactionStatus.PENDING_COMMIT);
    }

    @Nullable
    private static String truncate(@Nullable String message) {
        if (message == null) {
            return null;
        }
        return message.length() <= MAX_ERROR_LEN ? message : message.substring(0, MAX_ERROR_LEN);
    }
}
