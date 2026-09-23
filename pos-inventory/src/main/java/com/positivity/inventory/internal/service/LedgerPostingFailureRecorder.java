package com.positivity.inventory.internal.service;

import com.positivity.inventory.internal.entity.CycleCountAdjustment;
import com.positivity.inventory.internal.entity.ScrapRecord;
import com.positivity.inventory.internal.enums.AdjustmentStatus;
import com.positivity.inventory.internal.enums.ScrapStatus;
import com.positivity.inventory.internal.repository.CycleCountAdjustmentRepository;
import com.positivity.inventory.internal.repository.ScrapRecordRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Leaves a cycle count adjustment or scrap whose ledger posting failed unexpectedly {@code FAILED},
 * with the cause in {@code errorMessage}, where an operator can find it (#2170).
 *
 * <h2>Why after the rollback, in a transaction of its own</h2>
 *
 * The posting runs inside the approve or create transaction, and the posting exception rolls that
 * transaction back, so a {@code FAILED} save made inside it was rolled back with everything else and
 * no row could ever be left in that state. The failure is therefore written once the outer
 * transaction has rolled back, in a {@code REQUIRES_NEW} transaction:
 *
 * <ul>
 *   <li>after, not during: during, the outer transaction still holds the row lock its own update
 *       took, and a nested write to the same row would wait on a transaction that is waiting on it;
 *   <li>a row that exists (the approve path) is updated in place;
 *   <li>a row that does not (the below-threshold create path, whose insert was rolled back) is
 *       inserted under the id the caller was told about, so the id in the 500 names a real record.
 * </ul>
 *
 * The partial posting itself is still rolled back: only the failed record survives.
 *
 * <p>Recording is best effort. If it fails too, the cause is logged and the caller still gets the
 * original posting exception.
 */
@Slf4j
@Component
public class LedgerPostingFailureRecorder {

    private final CycleCountAdjustmentRepository adjustmentRepository;
    private final ScrapRecordRepository scrapRepository;
    private final TransactionTemplate failureTransaction;

    @PersistenceContext
    private EntityManager entityManager;

    public LedgerPostingFailureRecorder(
            CycleCountAdjustmentRepository adjustmentRepository,
            ScrapRecordRepository scrapRepository,
            PlatformTransactionManager transactionManager) {
        this.adjustmentRepository = adjustmentRepository;
        this.scrapRepository = scrapRepository;
        this.failureTransaction = new TransactionTemplate(transactionManager);
        this.failureTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Records {@code attempted} as {@code FAILED} once the current transaction has rolled back. The
     * state is copied now, so later changes to {@code attempted} do not leak into the record.
     *
     * <p>The posting fields ({@code ledgerEntryId}, {@code postedAt}) are cleared: a failure after the
     * ledger post returned rolls that ledger entry back too, so a FAILED record must not point at it.
     */
    public void recordAdjustmentFailure(@NonNull CycleCountAdjustment attempted, @Nullable String errorMessage) {
        CycleCountAdjustment failed = attempted.toBuilder()
                .status(AdjustmentStatus.FAILED)
                .errorMessage(errorMessage)
                .ledgerEntryId(null)
                .postedAt(null)
                .build();
        afterRollback(
                "adjustment " + failed.getAdjustmentId(),
                () -> adjustmentRepository
                        .findById(failed.getAdjustmentId())
                        .ifPresentOrElse(
                                row -> {
                                    row.setStatus(AdjustmentStatus.FAILED);
                                    row.setErrorMessage(errorMessage);
                                    row.setApprovedByUserId(failed.getApprovedByUserId());
                                    row.setApprovedAt(failed.getApprovedAt());
                                    row.setLedgerEntryId(null);
                                    row.setPostedAt(null);
                                    adjustmentRepository.save(row);
                                },
                                () -> entityManager.persist(failed)));
    }

    /** As {@link #recordAdjustmentFailure}, for a scrap record. */
    public void recordScrapFailure(@NonNull ScrapRecord attempted, @Nullable String errorMessage) {
        ScrapRecord failed = attempted.toBuilder()
                .status(ScrapStatus.FAILED)
                .errorMessage(errorMessage)
                .ledgerEntryId(null)
                .postedAt(null)
                .build();
        afterRollback(
                "scrap " + failed.getScrapId(),
                () -> scrapRepository
                        .findById(failed.getScrapId())
                        .ifPresentOrElse(
                                row -> {
                                    row.setStatus(ScrapStatus.FAILED);
                                    row.setErrorMessage(errorMessage);
                                    row.setApprovedBy(failed.getApprovedBy());
                                    row.setApprovedAt(failed.getApprovedAt());
                                    row.setLedgerEntryId(null);
                                    row.setPostedAt(null);
                                    scrapRepository.save(row);
                                },
                                () -> entityManager.persist(failed)));
    }

    private void afterRollback(String subject, Runnable write) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            record(subject, write);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                // A caller that caught the posting exception and committed anyway owns what it
                // committed; FAILED is written only over a transaction that did not.
                if (status != STATUS_COMMITTED) {
                    record(subject, write);
                }
            }
        });
    }

    private void record(String subject, Runnable write) {
        try {
            failureTransaction.executeWithoutResult(_ -> write.run());
            log.info("Recorded ledger posting failure for {}", subject);
        } catch (RuntimeException e) {
            log.error("Could not record ledger posting failure for {}", subject, e);
        }
    }
}
