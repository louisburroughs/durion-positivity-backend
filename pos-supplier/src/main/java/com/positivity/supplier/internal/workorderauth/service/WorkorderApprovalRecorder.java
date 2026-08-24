package com.positivity.supplier.internal.workorderauth.service;

import com.positivity.supplier.internal.entity.SupplierWorkorderAuthorizationEntity;
import com.positivity.supplier.internal.enums.WorkorderApprovalStatus;
import com.positivity.supplier.internal.repository.SupplierWorkorderAuthorizationRepository;
import java.time.Clock;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The three outcome writes of a completion-approval attempt, each in its own transaction.
 *
 * <h2>Why this is its own bean</h2>
 *
 * These methods used to sit on {@link WorkorderCompletionApprover} and be called on {@code this} from
 * its {@code @Scheduled} tick, which is not transactional. Spring's transaction advice is
 * proxy-based, so those self-calls bypassed it and the {@code @Transactional} annotations below were
 * never applied: each read-modify-write of the authorization row ran untransacted, so the row was
 * re-read detached and the attempt counter's read and write were not one unit. Living in a separate
 * bean means the calls cross the proxy and the boundaries are real.
 *
 * <p>Each write re-reads its row by id inside its own transaction and works on that, never on the
 * detached instance the tick handed over — saving that would merge a stale snapshot over a newer
 * row, and would resurrect the row outright if it had since been deleted.
 *
 * <h2>One transaction per row, not one per tick</h2>
 *
 * Deliberately per-attempt rather than around the whole batch. One vendor refusing must not roll back
 * the attempts already counted for the other workorders in the same tick — those counts are what
 * eventually escalates a stuck approval to a human, and losing them would let it retry forever while
 * looking healthy.
 */
@Slf4j
@Service
public class WorkorderApprovalRecorder {

    /** Longest review reason the column holds; a vendor message can be arbitrarily long. */
    private static final int MAX_REVIEW_REASON = 1000;

    private final SupplierWorkorderAuthorizationRepository authorizationRepository;
    private final Clock clock;

    /**
     * How many times an approval is attempted before a human is asked to look.
     *
     * <p>Finite on purpose. An approval that retries forever looks like an approval that is working,
     * and the money involved means somebody has to be told when it is not.
     */
    private final int maxAttempts;

    public WorkorderApprovalRecorder(
            SupplierWorkorderAuthorizationRepository authorizationRepository,
            Clock clock,
            @Value("${pos.supplier.workorderauth.approval-max-attempts:6}") int maxAttempts) {
        this.authorizationRepository = authorizationRepository;
        this.clock = clock;
        this.maxAttempts = maxAttempts;
    }

    @Transactional
    public void markApproved(@NonNull SupplierWorkorderAuthorizationEntity row) {
        SupplierWorkorderAuthorizationEntity managed = authorizationRepository
                .findById(row.getSupplierWorkorderAuthorizationId())
                .orElse(null);
        if (managed == null) {
            // Gone since the tick's query. Merging the detached snapshot back would resurrect a
            // deleted authorization, so there is nothing to record.
            return;
        }
        managed.setApprovalStatus(WorkorderApprovalStatus.APPROVED);
        managed.setApprovedAt(Instant.now(clock));
        managed.setApprovalAttempts(managed.getApprovalAttempts() + 1);
        authorizationRepository.save(managed);
        log.info(
                "Vendor approved completion of workorder {} at {}", managed.getWorkorderId(), managed.getSupplierRef());
    }

    /** Counts a failed attempt, and escalates once the budget is spent. */
    @Transactional
    public void recordAttempt(@NonNull SupplierWorkorderAuthorizationEntity row, @NonNull String reason) {
        SupplierWorkorderAuthorizationEntity managed = authorizationRepository
                .findById(row.getSupplierWorkorderAuthorizationId())
                .orElse(null);
        if (managed == null) {
            // Gone since the tick's query. Merging the detached snapshot back would resurrect a
            // deleted authorization, so there is nothing to record.
            return;
        }
        int attempts = managed.getApprovalAttempts() + 1;
        managed.setApprovalAttempts(attempts);
        if (attempts >= maxAttempts) {
            managed.setApprovalStatus(WorkorderApprovalStatus.MANUAL_REVIEW);
            managed.setReviewReason(truncate("completion approval gave up after " + attempts + " attempts: " + reason));
            log.error(
                    "Completion approval for workorder {} at {} needs review after {} attempts: {}",
                    managed.getWorkorderId(),
                    managed.getSupplierRef(),
                    attempts,
                    reason);
        }
        authorizationRepository.save(managed);
    }

    @Transactional
    public void park(@NonNull SupplierWorkorderAuthorizationEntity row, @NonNull String reason) {
        SupplierWorkorderAuthorizationEntity managed = authorizationRepository
                .findById(row.getSupplierWorkorderAuthorizationId())
                .orElse(null);
        if (managed == null) {
            // Gone since the tick's query. Merging the detached snapshot back would resurrect a
            // deleted authorization, so there is nothing to record.
            return;
        }
        managed.setApprovalStatus(WorkorderApprovalStatus.MANUAL_REVIEW);
        managed.setReviewReason(truncate(reason));
        authorizationRepository.save(managed);
        log.error(
                "Completion approval for workorder {} at {} cannot proceed: {}",
                managed.getWorkorderId(),
                managed.getSupplierRef(),
                reason);
    }

    @NonNull
    private static String truncate(@NonNull String reason) {
        return reason.length() <= MAX_REVIEW_REASON ? reason : reason.substring(0, MAX_REVIEW_REASON);
    }
}
