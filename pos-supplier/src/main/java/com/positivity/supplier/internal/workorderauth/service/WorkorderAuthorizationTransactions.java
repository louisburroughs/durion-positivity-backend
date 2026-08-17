package com.positivity.supplier.internal.workorderauth.service;

import com.positivity.supplier.internal.domain.model.SupplierRef;
import com.positivity.supplier.internal.domain.model.SupplierWorkorderAuthorization;
import com.positivity.supplier.internal.domain.model.WorkorderAuthorizationRequest;
import com.positivity.supplier.internal.entity.SupplierWorkorderAuthorizationEntity;
import com.positivity.supplier.internal.enums.WorkorderApprovalStatus;
import com.positivity.supplier.internal.enums.WorkorderAuthorizationStatus;
import com.positivity.supplier.internal.repository.SupplierWorkorderAuthorizationRepository;
import com.positivity.supplier.internal.service.SupplierProfileResolver.ResolvedBinding;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The three transaction boundaries a fleet authorization request steps through (CAP-323 #1229).
 *
 * <p>These used to be methods on {@link WorkorderAuthorizationRunner} itself, called from
 * {@code requestAuthorizationRow} as {@code this.openRow(...)}, {@code this.parkForReview(...)} and
 * {@code this.applyDecision(...)}. A self-invocation does not go through the Spring proxy, so none of
 * the {@code @Transactional} annotations on those methods ever took effect for that call path: every
 * write silently joined whatever transaction the caller was in, which for {@code
 * requestAuthorizationRow} was none. That defeated the documented guarantee that the row is committed
 * in its own transaction before the vendor is called, and that a review reason parked in {@code
 * REQUIRES_NEW} survives whatever the caller does next (SonarCloud java:S2229). Splitting these methods
 * into their own bean and calling them through the injected reference is what makes the proxy apply.
 *
 * <p>{@link WorkorderAuthorizationPoller} calling {@code applyDecision}/{@code parkForReview} through an
 * injected bean reference was never affected by this — only the self-call from inside {@code
 * WorkorderAuthorizationRunner} was.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkorderAuthorizationTransactions {

    private final SupplierWorkorderAuthorizationRepository authorizationRepository;
    private final WorkorderAuthorizationPublisher publisher;
    private final Clock clock;

    /**
     * Opens (or re-opens) the row for this workorder, committed before the vendor is called.
     *
     * <p>Re-requesting updates the existing row rather than adding a second. Two rows would let a
     * denial and a grant coexist for one workorder with nothing to say which one a shop should act
     * on, and the uniqueness index exists to make that impossible rather than merely unlikely.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @NonNull
    public SupplierWorkorderAuthorizationEntity openRow(
            @NonNull ResolvedBinding binding,
            @NonNull SupplierRef supplierRef,
            @NonNull WorkorderAuthorizationRequest request) {
        Instant now = Instant.now(clock);
        UUID vendorProfileId = binding.profile().getVendorProfileId();

        SupplierWorkorderAuthorizationEntity row = authorizationRepository
                .findByVendorProfileIdAndWorkorderId(vendorProfileId, request.workorderId())
                .orElseGet(() -> SupplierWorkorderAuthorizationEntity.builder()
                        .vendorProfileId(vendorProfileId)
                        .workorderId(request.workorderId())
                        .approvalStatus(WorkorderApprovalStatus.NOT_REQUESTED)
                        .approvalAttempts(0)
                        .build());

        row.setSupplierRef(supplierRef.value());
        row.setStatus(WorkorderAuthorizationStatus.PENDING);
        row.setRequestedAt(now);
        // Cleared on re-request: a stale reason from the previous attempt read as if it described
        // this one.
        row.setReviewReason(null);
        row.setReasonCode(null);
        row.setReasonText(null);
        row.setDecidedAt(null);
        row.setPollLocation(null);
        return authorizationRepository.save(row);
    }

    /**
     * Records a decision against an existing row and publishes it if terminal.
     *
     * <p>Shared with the poller, so an answer that arrives synchronously and one that arrives an
     * hour later are recorded and published by the same code. Two paths would drift, and the one
     * that drifts is the asynchronous one, which is also the one nobody watches.
     */
    @Transactional
    @NonNull
    public SupplierWorkorderAuthorizationEntity applyDecision(
            @NonNull SupplierWorkorderAuthorizationEntity row, @NonNull SupplierWorkorderAuthorization decision) {
        Instant now = Instant.now(clock);
        SupplierWorkorderAuthorizationEntity managed = authorizationRepository
                .findById(row.getSupplierWorkorderAuthorizationId())
                .orElse(row);

        managed.setLastPolledAt(now);
        if (decision.vendorAuthorizationId() != null) {
            managed.setVendorAuthorizationId(decision.vendorAuthorizationId());
        }

        switch (decision.status()) {
            case PENDING -> {
                managed.setStatus(WorkorderAuthorizationStatus.PENDING);
                if (decision.pollLocation() != null) {
                    managed.setPollLocation(decision.pollLocation());
                }
            }
            case GRANTED -> {
                managed.setStatus(WorkorderAuthorizationStatus.GRANTED);
                managed.setContractReference(decision.contractReference());
                managed.setAuthorizedAmount(decision.authorizedAmount());
                managed.setCurrency(decision.currency());
                managed.setDecidedAt(now);
            }
            case DENIED -> {
                managed.setStatus(WorkorderAuthorizationStatus.DENIED);
                managed.setReasonCode(decision.vendorReasonCode());
                managed.setReasonText(decision.vendorReason());
                managed.setDecidedAt(now);
            }
            case NOT_FOUND -> {
                managed.setStatus(WorkorderAuthorizationStatus.NOT_FOUND);
                managed.setReasonCode(decision.vendorReasonCode());
                managed.setReasonText(decision.vendorReason());
                managed.setDecidedAt(now);
            }
        }

        SupplierWorkorderAuthorizationEntity saved = authorizationRepository.save(managed);

        // Published inside the same transaction as the state change, through the outbox: a decision
        // recorded but not published would leave the rest of the platform believing the fleet never
        // answered (ADR-0044 §4).
        publisher.publishIfTerminal(saved, now);
        return saved;
    }

    /**
     * Parks a row for a human and says why.
     *
     * <p>{@code REQUIRES_NEW} so the reason survives whatever the caller does next. A review reason
     * rolled back with the transaction that discovered it is the one piece of information nobody can
     * reconstruct afterwards.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @NonNull
    public SupplierWorkorderAuthorizationEntity parkForReview(
            @NonNull SupplierWorkorderAuthorizationEntity row, @NonNull String reason) {
        SupplierWorkorderAuthorizationEntity managed = authorizationRepository
                .findById(row.getSupplierWorkorderAuthorizationId())
                .orElse(row);
        managed.setStatus(WorkorderAuthorizationStatus.MANUAL_REVIEW);
        managed.setReviewReason(truncate(reason));
        managed.setLastPolledAt(Instant.now(clock));
        log.warn(
                "Workorder authorization for workorder {} at {} needs review: {}",
                managed.getWorkorderId(),
                managed.getSupplierRef(),
                reason);
        return authorizationRepository.save(managed);
    }

    /** Keeps a vendor's error text inside the column it is stored in. */
    @NonNull
    private static String truncate(@NonNull String reason) {
        return reason.length() <= 1000 ? reason : reason.substring(0, 1000);
    }
}
