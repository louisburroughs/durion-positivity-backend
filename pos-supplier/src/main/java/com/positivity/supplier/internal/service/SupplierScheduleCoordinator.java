package com.positivity.supplier.internal.service;

import com.positivity.supplier.internal.repository.SupplierScheduleLeaseRepository;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lease-guarded execution of one binding's scheduled run (binding decision 4).
 *
 * <p>Owns the claim / page / checkpoint / heartbeat / release protocol so that the capability work
 * itself — arriving with the codecs in CAP-318 — only has to implement "process one page and tell me
 * the window you finished". Every correctness property of the protocol lives here and is tested here,
 * rather than being re-implemented per capability.
 *
 * <h2>Transaction boundaries are the substance of this class</h2>
 *
 * {@link #runPage} is {@code REQUIRED} and deliberately <strong>not</strong> {@code REQUIRES_NEW}: the
 * checkpoint advance must commit in the <em>same</em> transaction as the page it describes. If the two
 * could commit independently, a page could roll back while its checkpoint stuck, and that window would
 * never be reprocessed — a silent data gap that no error surfaces.
 *
 * <p>Note the deliberate contrast with the module's two other write-alongside-work concerns. All three
 * differ on purpose:
 *
 * <ol>
 *   <li>{@code ExchangeAuditObserver} — {@code REQUIRES_NEW}, swallows failures, because live vendor
 *       traffic must not fail when the audit sink is down.
 *   <li><strong>This class</strong> — {@code REQUIRED}, rolls back with its page, because a checkpoint
 *       committing independently silently skips a window.
 *   <li>{@code AuditAccessRecorder} — {@code MANDATORY}, fails the read, because ADR-0050 §7 makes the
 *       access record a precondition of access rather than a side effect.
 * </ol>
 *
 * <p>Do not unify them, and do not copy the observer's pattern here.
 *
 * <h2>Stolen leases</h2>
 *
 * The owner guard on every write is what makes takeover safe. A run whose lease expired mid-work will
 * find {@link #stillOwns} false and must abort <em>before</em> its next page; if it tries anyway, the
 * owner-guarded checkpoint update affects zero rows and {@link #runPage} raises, so it cannot
 * half-apply work the new owner is also doing.
 */
@Service
public class SupplierScheduleCoordinator {

    private static final Logger log = LoggerFactory.getLogger(SupplierScheduleCoordinator.class);

    private final SupplierScheduleLeaseRepository leaseRepository;
    private final int leaseSeconds;

    public SupplierScheduleCoordinator(
            @NonNull SupplierScheduleLeaseRepository leaseRepository,
            @Value("${pos.supplier.schedule.lease-duration-seconds:600}") int leaseSeconds) {
        this.leaseRepository = Objects.requireNonNull(leaseRepository, "leaseRepository");
        if (leaseSeconds <= 0) {
            throw new IllegalStateException(
                    "pos.supplier.schedule.lease-duration-seconds must be positive, was " + leaseSeconds);
        }
        // Decision 4 sets the lease at max(2x run, 10min); 600s is that floor. A lease shorter than a
        // run's page time would be stolen mid-page on every run.
        this.leaseSeconds = leaseSeconds;
    }

    /**
     * Attempts to claim a binding's schedule lease.
     *
     * @param bindingId the binding to claim
     * @param ownerToken token identifying this run; use {@link #newOwnerToken()}
     * @return {@code true} when this run may proceed, {@code false} when another instance holds it —
     *     which is the normal, expected outcome on every instance but one
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean tryClaim(@NonNull UUID bindingId, @NonNull String ownerToken) {
        boolean claimed = leaseRepository.claim(bindingId, ownerToken, leaseSeconds) == 1;
        if (claimed) {
            log.debug("Claimed schedule lease for binding {} as {}", bindingId, ownerToken);
        }
        return claimed;
    }

    /**
     * Runs one page of work and advances the checkpoint <strong>in the same transaction</strong>.
     *
     * <p>The ordering inside is deliberate: the page executes first, then the checkpoint advance is
     * attempted, and a zero-row result aborts the transaction. That way a stolen lease rolls the page
     * back rather than leaving it applied without a checkpoint.
     *
     * @param bindingId the binding being processed
     * @param ownerToken this run's token
     * @param page the work for one page; returns the end of the window it completed
     * @throws LeaseLostException when this run no longer owns the lease, rolling the page back
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public void runPage(@NonNull UUID bindingId, @NonNull String ownerToken, @NonNull SchedulePage page) {
        Instant completedWindowEnd = page.process();
        Objects.requireNonNull(completedWindowEnd, "a page must report the window end it completed");

        int advanced = leaseRepository.advanceCheckpoint(bindingId, ownerToken, completedWindowEnd);
        if (advanced != 1) {
            // Raising rolls back the page too, which is the point: the new owner will reprocess this
            // window from the last committed checkpoint, so the work is done exactly once.
            throw new LeaseLostException(bindingId, ownerToken);
        }
    }

    /**
     * Extends the lease mid-run.
     *
     * @return {@code false} when the lease was lost, in which case the run must abort before its next
     *     page rather than continue on the assumption it still owns the binding
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean heartbeat(@NonNull UUID bindingId, @NonNull String ownerToken) {
        boolean extended = leaseRepository.heartbeat(bindingId, ownerToken, leaseSeconds) == 1;
        if (!extended) {
            log.warn(
                    "Lost schedule lease for binding {} while running as {}; aborting before the next page",
                    bindingId,
                    ownerToken);
        }
        return extended;
    }

    /**
     * Whether this run still holds a live lease. Checked <em>between</em> pages so a run that lost its
     * lease stops cleanly instead of discovering it by failing a checkpoint.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public boolean stillOwns(@NonNull UUID bindingId, @NonNull String ownerToken) {
        return leaseRepository.countLiveOwnership(bindingId, ownerToken) == 1;
    }

    /**
     * Releases the lease so another instance can pick the binding up without waiting for expiry.
     *
     * <p>Owner-guarded, so a run that already lost its lease cannot release the new holder's claim.
     * Safe to call unconditionally in a {@code finally}: releasing a lease you do not hold is a no-op.
     *
     * @param outcome recorded as the run's last outcome, for operator visibility
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void release(@NonNull UUID bindingId, @NonNull String ownerToken, @NonNull String outcome) {
        leaseRepository.release(bindingId, ownerToken, outcome);
    }

    /**
     * A fresh owner token. Random per run rather than derived from hostname or pod name: two runs on
     * one instance must not share a token, or the owner guard could not tell them apart.
     */
    @NonNull
    public static String newOwnerToken() {
        return UUID.randomUUID().toString();
    }

    /** One page of scheduled work; returns the end of the window it completed. */
    @FunctionalInterface
    public interface SchedulePage {

        /**
         * Processes one page.
         *
         * @return the end of the window completed, which becomes the new checkpoint
         */
        @NonNull
        Instant process();
    }

    /**
     * Raised when a run's lease was taken over. Rolls back the page in progress, which is what makes
     * takeover safe rather than merely detected.
     */
    public static class LeaseLostException extends RuntimeException {

        public LeaseLostException(@NonNull UUID bindingId, @NonNull String ownerToken) {
            super("Schedule lease for binding " + bindingId + " is no longer held by " + ownerToken
                    + "; the page has been rolled back and will be reprocessed by the new owner");
        }
    }
}
