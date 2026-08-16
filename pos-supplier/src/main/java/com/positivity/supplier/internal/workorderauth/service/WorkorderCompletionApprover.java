package com.positivity.supplier.internal.workorderauth.service;

import com.positivity.supplier.internal.adapter.michelins2s.MichelinS2SWorkorderAuthCodec;
import com.positivity.supplier.internal.client.SupplierBaseClient;
import com.positivity.supplier.internal.client.SupplierHttpRequest;
import com.positivity.supplier.internal.client.SupplierHttpResponse;
import com.positivity.supplier.internal.domain.model.SupplierCapability;
import com.positivity.supplier.internal.domain.model.SupplierRef;
import com.positivity.supplier.internal.domain.model.SupplierRequestSpec;
import com.positivity.supplier.internal.entity.SupplierWorkorderAuthorizationEntity;
import com.positivity.supplier.internal.enums.WorkorderApprovalStatus;
import com.positivity.supplier.internal.enums.WorkorderAuthorizationStatus;
import com.positivity.supplier.internal.registry.AdapterRegistry;
import com.positivity.supplier.internal.registry.AdapterResolution;
import com.positivity.supplier.internal.repository.SupplierWorkorderAuthorizationRepository;
import com.positivity.supplier.internal.service.SupplierProfileResolver;
import com.positivity.supplier.internal.service.SupplierProfileResolver.ResolvedBinding;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Limit;
import org.springframework.http.HttpMethod;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tells the fleet the authorized work is done (CAP-323 #1229).
 *
 * <h2>This is the step that gets a shop paid</h2>
 *
 * An authorization that is never approved on completion is work already performed that the fleet has
 * not agreed to pay for. Unlike a failed fetch, it cannot be recovered by asking again later once
 * anyone notices, because by then the job has been invoiced and closed. So this retries on its own
 * schedule and, when it stops retrying, it says so loudly rather than quietly.
 *
 * <h2>Retried, unlike the authorization request</h2>
 *
 * Approving an already-approved workorder is the vendor restating a fact, so a repeat is harmless —
 * the opposite of the create call, where a repeat can open a second authorization. That asymmetry is
 * why the two live in different classes with different rules rather than sharing a "send with
 * retry" helper that would have to be right about both.
 */
@Slf4j
@Service
public class WorkorderCompletionApprover {

    private final SupplierWorkorderAuthorizationRepository authorizationRepository;
    private final SupplierProfileResolver profileResolver;
    private final AdapterRegistry adapterRegistry;
    private final SupplierBaseClient baseClient;
    private final Clock clock;

    /** How many outstanding approvals one tick will attempt. */
    private final int batchSize;

    /**
     * How many times an approval is attempted before a human is asked to look.
     *
     * <p>Finite on purpose. An approval that retries forever looks like an approval that is working,
     * and the money involved means somebody has to be told when it is not.
     */
    private final int maxAttempts;

    public WorkorderCompletionApprover(
            SupplierWorkorderAuthorizationRepository authorizationRepository,
            SupplierProfileResolver profileResolver,
            AdapterRegistry adapterRegistry,
            SupplierBaseClient baseClient,
            Clock clock,
            @Value("${pos.supplier.workorderauth.approval-batch-size:50}") int batchSize,
            @Value("${pos.supplier.workorderauth.approval-max-attempts:6}") int maxAttempts) {
        this.authorizationRepository = authorizationRepository;
        this.profileResolver = profileResolver;
        this.adapterRegistry = adapterRegistry;
        this.baseClient = baseClient;
        this.clock = clock;
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
    }

    /**
     * Marks a completed workorder as needing vendor sign-off.
     *
     * <p>Recorded rather than sent immediately. The completion event that triggers this is consumed
     * in a transaction that must not depend on a vendor being reachable — a fleet API being down is
     * not a reason to fail the handling of a workorder completion.
     *
     * @return the row queued for approval, or empty when this workorder has no live authorization
     *     (most workorders are not fleet work at all)
     */
    @Transactional
    @NonNull
    public Optional<SupplierWorkorderAuthorizationEntity> queueApproval(@NonNull UUID workorderId) {
        List<SupplierWorkorderAuthorizationEntity> rows =
                authorizationRepository.findByWorkorderIdOrderByRequestedAtDesc(workorderId);
        for (SupplierWorkorderAuthorizationEntity row : rows) {
            if (row.getStatus() != WorkorderAuthorizationStatus.GRANTED) {
                continue;
            }
            if (row.getApprovalStatus() == WorkorderApprovalStatus.APPROVED) {
                // Already signed off. A redelivered completion event must not reopen it.
                return Optional.of(row);
            }
            row.setApprovalStatus(WorkorderApprovalStatus.PENDING);
            return Optional.of(authorizationRepository.save(row));
        }
        return Optional.empty();
    }

    /** Attempts outstanding approvals, oldest first. */
    @Scheduled(fixedDelayString = "${pos.supplier.workorderauth.approval-interval-ms:300000}")
    public void approveOutstanding() {
        List<SupplierWorkorderAuthorizationEntity> outstanding =
                authorizationRepository.findByApprovalStatusOrderByUpdatedAtAsc(
                        WorkorderApprovalStatus.PENDING, Limit.of(batchSize));

        for (SupplierWorkorderAuthorizationEntity row : outstanding) {
            try {
                approveOne(row);
            } catch (Exception e) {
                log.warn(
                        "Approval of workorder {} at {} failed: {}",
                        row.getWorkorderId(),
                        row.getSupplierRef(),
                        e.toString(),
                        e);
                recordAttempt(row, "approval attempt threw: " + e);
            }
        }
    }

    private void approveOne(@NonNull SupplierWorkorderAuthorizationEntity row) {
        String vendorAuthorizationId = row.getVendorAuthorizationId();
        if (vendorAuthorizationId == null || vendorAuthorizationId.isBlank()) {
            // Granted, but the vendor never told us what to call it. No amount of retrying invents
            // an identifier, so this goes to a human immediately rather than burning attempts.
            park(row, "the vendor granted this authorization without an id, so its completion cannot be approved");
            return;
        }

        SupplierRef supplierRef = new SupplierRef(row.getSupplierRef());
        ResolvedBinding binding =
                profileResolver.resolveBinding(supplierRef, SupplierCapability.WORKORDER_AUTHORIZATION);
        MichelinS2SWorkorderAuthCodec codec = resolveCodec(binding);
        String partnerId =
                profileResolver.resolvePartyContext(supplierRef, null).billing().getAccountNumber();

        SupplierRequestSpec spec =
                codec.buildApprovalRequest(vendorAuthorizationId, partnerId == null ? "" : partnerId, "1");
        SupplierHttpResponse response = baseClient.exchange(toHttpRequest(binding, spec));

        if (response.isSuccess()) {
            markApproved(row);
            return;
        }
        recordAttempt(
                row,
                "vendor refused or could not be reached: " + response.outcome()
                        + (response.failureDetail() == null ? "" : " — " + response.failureDetail()));
    }

    @Transactional
    void markApproved(@NonNull SupplierWorkorderAuthorizationEntity row) {
        SupplierWorkorderAuthorizationEntity managed = authorizationRepository
                .findById(row.getSupplierWorkorderAuthorizationId())
                .orElse(row);
        managed.setApprovalStatus(WorkorderApprovalStatus.APPROVED);
        managed.setApprovedAt(Instant.now(clock));
        managed.setApprovalAttempts(managed.getApprovalAttempts() + 1);
        authorizationRepository.save(managed);
        log.info(
                "Vendor approved completion of workorder {} at {}", managed.getWorkorderId(), managed.getSupplierRef());
    }

    /** Counts a failed attempt, and escalates once the budget is spent. */
    @Transactional
    void recordAttempt(@NonNull SupplierWorkorderAuthorizationEntity row, @NonNull String reason) {
        SupplierWorkorderAuthorizationEntity managed = authorizationRepository
                .findById(row.getSupplierWorkorderAuthorizationId())
                .orElse(row);
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
    void park(@NonNull SupplierWorkorderAuthorizationEntity row, @NonNull String reason) {
        SupplierWorkorderAuthorizationEntity managed = authorizationRepository
                .findById(row.getSupplierWorkorderAuthorizationId())
                .orElse(row);
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
    private SupplierHttpRequest toHttpRequest(@NonNull ResolvedBinding binding, @NonNull SupplierRequestSpec spec) {
        return new SupplierHttpRequest(
                binding,
                HttpMethod.valueOf(spec.method()),
                spec.pathSuffix(),
                spec.queryParams(),
                spec.body(),
                spec.contentType(),
                spec.accept(),
                spec.idempotent(),
                spec.headers());
    }

    @NonNull
    private MichelinS2SWorkorderAuthCodec resolveCodec(@NonNull ResolvedBinding binding) {
        AdapterResolution resolution = adapterRegistry.resolve(
                SupplierCapability.WORKORDER_AUTHORIZATION, binding.family(), binding.version());
        if (resolution instanceof AdapterResolution.Resolved resolved
                && resolved.codec() instanceof MichelinS2SWorkorderAuthCodec codec) {
            return codec;
        }
        throw new IllegalStateException("No workorder authorization codec registered for " + binding.family());
    }

    @NonNull
    private static String truncate(@NonNull String reason) {
        return reason.length() <= 1000 ? reason : reason.substring(0, 1000);
    }
}
