package com.positivity.warranty.internal.service;

import com.positivity.domainevents.DomainEventEnvelope;
import com.positivity.domainevents.DomainTopics;
import com.positivity.domainevents.warranty.WarrantyReimbursementResolvedV1;
import com.positivity.domainevents.warranty.WarrantyReimbursementSubmittedV1;
import com.positivity.security.common.SecurityContextHelper;
import com.positivity.warranty.internal.config.OutboxEventWriter;
import com.positivity.warranty.internal.dto.ReimbursementResponse;
import com.positivity.warranty.internal.dto.ReimbursementSubmitRequest;
import com.positivity.warranty.internal.dto.ReimbursementUpdateRequest;
import com.positivity.warranty.internal.entity.VendorReimbursement;
import com.positivity.warranty.internal.entity.WarrantyClaim;
import com.positivity.warranty.internal.entity.WarrantyProvider;
import com.positivity.warranty.internal.enums.ClaimStatus;
import com.positivity.warranty.internal.enums.ProviderType;
import com.positivity.warranty.internal.enums.ReimbursementStatus;
import com.positivity.warranty.internal.exception.IllegalClaimStateException;
import com.positivity.warranty.internal.exception.WarrantyNotFoundException;
import com.positivity.warranty.internal.repository.VendorReimbursementRepository;
import com.positivity.warranty.internal.repository.WarrantyClaimRepository;
import com.positivity.warranty.internal.repository.WarrantyProviderRepository;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Vendor-reimbursement child lifecycle (PRD §3.7). Settle-the-customer-first: this flow only
 * opens once the claim is {@code APPROVED} or {@code SETTLED} and never touches the claim's own
 * state machine. Submission and every vendor resolution enqueue outbox events (PRD §9.3) that
 * pos-accounting consumes to match expected vendor credits — warranty never writes to accounting.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReimbursementServiceImpl implements ReimbursementService {

    static final String CLAIM_NOT_FOUND_CODE = "WARRANTY_CLAIM_NOT_FOUND";
    static final String PROVIDER_NOT_FOUND_CODE = "WARRANTY_PROVIDER_NOT_FOUND";
    static final String REIMBURSEMENT_NOT_FOUND_CODE = "WARRANTY_REIMBURSEMENT_NOT_FOUND";
    static final String CLAIM_STATE_CODE = "WARRANTY_REIMBURSEMENT_CLAIM_STATE";
    static final String NO_PROVIDER_CODE = "WARRANTY_REIMBURSEMENT_NO_PROVIDER";
    static final String NOT_APPLICABLE_CODE = "WARRANTY_REIMBURSEMENT_NOT_APPLICABLE";
    static final String ALREADY_SUBMITTED_CODE = "WARRANTY_REIMBURSEMENT_ALREADY_SUBMITTED";
    static final String ILLEGAL_TRANSITION_CODE = "WARRANTY_REIMBURSEMENT_ILLEGAL_TRANSITION";

    private static final String SYSTEM = "system";

    /** Claim states in which reimbursement submission is allowed (settle customer first, PRD §2). */
    private static final Set<ClaimStatus> SUBMITTABLE_CLAIM_STATES =
            EnumSet.of(ClaimStatus.APPROVED, ClaimStatus.SETTLED);

    /** Statuses the update endpoint may target; source states are checked via {@link #LEGAL_MOVES}. */
    private static final Set<ReimbursementStatus> UPDATABLE_TARGETS = EnumSet.of(
            ReimbursementStatus.APPROVED,
            ReimbursementStatus.PARTIALLY_APPROVED,
            ReimbursementStatus.DENIED,
            ReimbursementStatus.CREDIT_RECEIVED,
            ReimbursementStatus.WRITTEN_OFF);

    /** Vendor resolutions that emit {@code warranty.reimbursement.resolved} (PRD §9.3). */
    private static final Set<ReimbursementStatus> RESOLUTION_STATES = EnumSet.of(
            ReimbursementStatus.APPROVED,
            ReimbursementStatus.PARTIALLY_APPROVED,
            ReimbursementStatus.DENIED,
            ReimbursementStatus.CREDIT_RECEIVED);

    /**
     * Child state machine (PRD §3.7): NOT_SUBMITTED → SUBMITTED → APPROVED | PARTIALLY_APPROVED
     * | DENIED, then CREDIT_RECEIVED; any non-terminal state may be WRITTEN_OFF. DENIED,
     * CREDIT_RECEIVED, WRITTEN_OFF, and NOT_APPLICABLE are terminal (they unblock claim CLOSE).
     */
    private static final Map<ReimbursementStatus, Set<ReimbursementStatus>> LEGAL_MOVES = new EnumMap<>(Map.of(
            ReimbursementStatus.NOT_SUBMITTED,
            EnumSet.of(ReimbursementStatus.SUBMITTED, ReimbursementStatus.WRITTEN_OFF),
            ReimbursementStatus.SUBMITTED,
            EnumSet.of(
                    ReimbursementStatus.APPROVED,
                    ReimbursementStatus.PARTIALLY_APPROVED,
                    ReimbursementStatus.DENIED,
                    ReimbursementStatus.WRITTEN_OFF),
            ReimbursementStatus.APPROVED,
            EnumSet.of(ReimbursementStatus.CREDIT_RECEIVED, ReimbursementStatus.WRITTEN_OFF),
            ReimbursementStatus.PARTIALLY_APPROVED,
            EnumSet.of(ReimbursementStatus.CREDIT_RECEIVED, ReimbursementStatus.WRITTEN_OFF),
            ReimbursementStatus.DENIED,
            EnumSet.noneOf(ReimbursementStatus.class),
            ReimbursementStatus.CREDIT_RECEIVED,
            EnumSet.noneOf(ReimbursementStatus.class),
            ReimbursementStatus.WRITTEN_OFF,
            EnumSet.noneOf(ReimbursementStatus.class),
            ReimbursementStatus.NOT_APPLICABLE,
            EnumSet.noneOf(ReimbursementStatus.class)));

    private final WarrantyClaimRepository claimRepository;
    private final WarrantyProviderRepository providerRepository;
    private final VendorReimbursementRepository reimbursementRepository;
    private final Clock clock;
    private final EntityManager entityManager;
    private final ObjectProvider<OutboxEventWriter> outboxEventWriter;

    @Override
    @NonNull
    @Transactional
    public ReimbursementResponse submit(@NonNull UUID claimId, @NonNull ReimbursementSubmitRequest request) {
        WarrantyClaim claim = loadClaim(claimId);
        if (!SUBMITTABLE_CLAIM_STATES.contains(claim.getStatus())) {
            throw new IllegalClaimStateException(
                    CLAIM_STATE_CODE,
                    "Claim " + claim.getClaimCode() + " is " + claim.getStatus()
                            + "; reimbursement can only be submitted for an APPROVED or SETTLED claim",
                    "Decide the claim (approve) and settle the customer before submitting the vendor reimbursement.");
        }
        if (claim.getProviderId() == null) {
            throw new IllegalClaimStateException(
                    NO_PROVIDER_CODE,
                    "Claim " + claim.getClaimCode() + " has no warranty provider assigned",
                    "Assign the covering provider/policy to the claim before submitting a vendor reimbursement.");
        }
        WarrantyProvider provider = providerRepository
                .findById(claim.getProviderId())
                .orElseThrow(() -> new WarrantyNotFoundException(
                        PROVIDER_NOT_FOUND_CODE, "Warranty provider not found: " + claim.getProviderId()));
        if (provider.getProviderType() == ProviderType.DEALER) {
            throw new IllegalClaimStateException(
                    NOT_APPLICABLE_CODE,
                    "Provider " + provider.getName()
                            + " is dealer-funded; vendor reimbursement is NOT_APPLICABLE for self-funded programs",
                    "No vendor reimbursement is needed — close the claim once settlements and part returns"
                            + " are terminal.");
        }

        VendorReimbursement reimbursement = reimbursementRepository.findByClaimId(claimId).stream()
                .findFirst()
                .orElseGet(() -> VendorReimbursement.builder()
                        .claimId(claimId)
                        .providerId(claim.getProviderId())
                        .status(ReimbursementStatus.NOT_SUBMITTED)
                        .build());
        if (reimbursement.getStatus() != ReimbursementStatus.NOT_SUBMITTED) {
            throw new IllegalClaimStateException(
                    ALREADY_SUBMITTED_CODE,
                    "Reimbursement for claim " + claim.getClaimCode() + " is already " + reimbursement.getStatus(),
                    nextActionFor(reimbursement.getStatus()));
        }

        Instant now = Instant.now(clock);
        reimbursement.setProviderId(claim.getProviderId());
        reimbursement.setStatus(ReimbursementStatus.SUBMITTED);
        reimbursement.setAmountRequested(request.amountRequested());
        reimbursement.setVendorClaimReference(request.vendorClaimReference());
        if (request.notes() != null) {
            reimbursement.setNotes(request.notes());
        }
        reimbursement.setSubmittedAt(now);
        reimbursement.setSubmittedBy(SecurityContextHelper.getCurrentUsernameOrDefault(SYSTEM));
        VendorReimbursement saved = reimbursementRepository.save(reimbursement);

        publishSubmitted(claim, provider, saved, now);
        return ReimbursementResponse.from(saved);
    }

    @Override
    @NonNull
    @Transactional
    public ReimbursementResponse update(@NonNull UUID claimId, @NonNull ReimbursementUpdateRequest request) {
        WarrantyClaim claim = loadClaim(claimId);
        VendorReimbursement reimbursement = reimbursementRepository.findByClaimId(claimId).stream()
                .findFirst()
                .orElseThrow(() -> new WarrantyNotFoundException(
                        REIMBURSEMENT_NOT_FOUND_CODE,
                        "Claim " + claim.getClaimCode() + " has no vendor reimbursement record"));

        ReimbursementStatus target = request.status();
        if (!UPDATABLE_TARGETS.contains(target)) {
            throw new IllegalArgumentException("status must be one of " + UPDATABLE_TARGETS + " but was " + target);
        }
        ReimbursementStatus current = reimbursement.getStatus();
        if (!LEGAL_MOVES.getOrDefault(current, Set.of()).contains(target)) {
            throw new IllegalClaimStateException(
                    ILLEGAL_TRANSITION_CODE,
                    "Reimbursement for claim " + claim.getClaimCode() + " cannot move " + current + " -> " + target,
                    nextActionFor(current));
        }
        if ((target == ReimbursementStatus.APPROVED || target == ReimbursementStatus.PARTIALLY_APPROVED)
                && request.amountApproved() == null) {
            throw new IllegalArgumentException("amountApproved is required when status is " + target);
        }

        Instant now = Instant.now(clock);
        reimbursement.setStatus(target);
        if (request.amountApproved() != null) {
            reimbursement.setAmountApproved(request.amountApproved());
        }
        if (request.creditReference() != null) {
            reimbursement.setCreditReference(request.creditReference());
        }
        if (request.notes() != null) {
            reimbursement.setNotes(request.notes());
        }
        if (reimbursement.getResolvedAt() == null) {
            reimbursement.setResolvedAt(now);
        }
        if (target == ReimbursementStatus.CREDIT_RECEIVED) {
            reimbursement.setCreditReceivedAt(now);
        }
        VendorReimbursement saved = reimbursementRepository.save(reimbursement);

        if (RESOLUTION_STATES.contains(target)) {
            publishResolved(claim, saved, now);
        }
        return ReimbursementResponse.from(saved);
    }

    @Override
    @NonNull
    @Transactional(readOnly = true)
    public List<ReimbursementResponse> worklist(@Nullable ReimbursementStatus status, @Nullable UUID providerId) {
        List<VendorReimbursement> results;
        if (status != null && providerId != null) {
            results = reimbursementRepository.findByStatusAndProviderId(status, providerId);
        } else if (status != null) {
            results = reimbursementRepository.findByStatus(status);
        } else if (providerId != null) {
            results = reimbursementRepository.findByProviderId(providerId);
        } else {
            results = reimbursementRepository.findAll();
        }
        return results.stream().map(ReimbursementResponse::from).toList();
    }

    // ------------------------------------------------------------------ internals

    private @NonNull WarrantyClaim loadClaim(@NonNull UUID claimId) {
        return claimRepository
                .findById(claimId)
                .orElseThrow(() ->
                        new WarrantyNotFoundException(CLAIM_NOT_FOUND_CODE, "Warranty claim not found: " + claimId));
    }

    /** Human next step for the 409 {@code nextAction} field (PRD §5). */
    private static String nextActionFor(ReimbursementStatus current) {
        Set<ReimbursementStatus> moves = LEGAL_MOVES.getOrDefault(current, Set.of());
        if (moves.isEmpty()) {
            return "Reimbursement is terminal (" + current + "); no further transitions are possible.";
        }
        return "Legal transitions from " + current + ": " + moves + ".";
    }

    private void publishSubmitted(
            WarrantyClaim claim, WarrantyProvider provider, VendorReimbursement reimbursement, Instant submittedAt) {
        OutboxEventWriter writer = outboxEventWriter.getIfAvailable();
        if (writer == null) {
            return;
        }
        // Flush so generated ids and the claim @Version carry committed values (pos-invoice
        // pattern) before the envelope snapshots them.
        entityManager.flush();
        WarrantyReimbursementSubmittedV1 payload = new WarrantyReimbursementSubmittedV1(
                claim.getId(),
                claim.getClaimCode(),
                reimbursement.getId(),
                reimbursement.getProviderId(),
                provider.getApVendorId(),
                reimbursement.getAmountRequested(),
                reimbursement.getVendorClaimReference(),
                submittedAt);
        writer.publish(
                DomainTopics.events("warranty"),
                envelope(
                        WarrantyReimbursementSubmittedV1.EVENT_TYPE,
                        WarrantyReimbursementSubmittedV1.SCHEMA_VERSION,
                        claim,
                        payload));
        log.debug("Queued {} claimId={}", WarrantyReimbursementSubmittedV1.EVENT_TYPE, claim.getId());
    }

    private void publishResolved(WarrantyClaim claim, VendorReimbursement reimbursement, Instant resolvedAt) {
        OutboxEventWriter writer = outboxEventWriter.getIfAvailable();
        if (writer == null) {
            return;
        }
        entityManager.flush();
        UUID apVendorId = providerRepository
                .findById(reimbursement.getProviderId())
                .map(WarrantyProvider::getApVendorId)
                .orElse(null);
        WarrantyReimbursementResolvedV1 payload = new WarrantyReimbursementResolvedV1(
                claim.getId(),
                claim.getClaimCode(),
                reimbursement.getId(),
                reimbursement.getProviderId(),
                apVendorId,
                reimbursement.getStatus().name(),
                reimbursement.getAmountApproved(),
                reimbursement.getCreditReference(),
                resolvedAt);
        writer.publish(
                DomainTopics.events("warranty"),
                envelope(
                        WarrantyReimbursementResolvedV1.EVENT_TYPE,
                        WarrantyReimbursementResolvedV1.SCHEMA_VERSION,
                        claim,
                        payload));
        log.debug(
                "Queued {} claimId={} status={}",
                WarrantyReimbursementResolvedV1.EVENT_TYPE,
                claim.getId(),
                reimbursement.getStatus());
    }

    private <T> DomainEventEnvelope<T> envelope(String eventType, int schemaVersion, WarrantyClaim claim, T payload) {
        return DomainEventEnvelope.of(
                eventType,
                schemaVersion,
                claim.getId(),
                claim.getVersion() == null ? 0L : claim.getVersion(),
                "pos-warranty",
                null,
                SecurityContextHelper.getCurrentUsernameOrDefault(SYSTEM),
                payload,
                clock);
    }
}
