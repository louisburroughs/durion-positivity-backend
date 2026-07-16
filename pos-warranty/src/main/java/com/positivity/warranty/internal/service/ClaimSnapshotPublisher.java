package com.positivity.warranty.internal.service;

import com.positivity.domainevents.DomainEventEnvelope;
import com.positivity.domainevents.DomainTopics;
import com.positivity.domainevents.warranty.WarrantyClaimSnapshotV1;
import com.positivity.security.common.SecurityContextHelper;
import com.positivity.warranty.internal.config.OutboxEventWriter;
import com.positivity.warranty.internal.entity.ClaimSettlement;
import com.positivity.warranty.internal.entity.PartReturn;
import com.positivity.warranty.internal.entity.VendorReimbursement;
import com.positivity.warranty.internal.entity.WarrantyClaim;
import com.positivity.warranty.internal.entity.WarrantyClaimLine;
import com.positivity.warranty.internal.repository.ClaimSettlementRepository;
import com.positivity.warranty.internal.repository.PartReturnRepository;
import com.positivity.warranty.internal.repository.VendorReimbursementRepository;
import com.positivity.warranty.internal.repository.WarrantyClaimRepository;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Enqueues a full {@code warranty.claim.snapshot} outbox event (ADR-0044 R3 path) so other
 * modules can build event-fed read replicas of warranty claims without any synchronous client
 * into this module.
 *
 * <p>Must be called inside the mutating business transaction, alongside (never instead of) the
 * granular money-lifecycle emissions: the outbox write shares the caller's transaction
 * ({@code MANDATORY} on {@link OutboxEventWriter#publish}), so a snapshot exists if and only if
 * the mutation committed. Degrades to a no-op when Kafka is disabled, same as the existing
 * emitters ({@code ObjectProvider} — the writer bean only exists when
 * {@code pos.warranty.kafka.enabled=true}).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ClaimSnapshotPublisher {

    private static final String SYSTEM = "system";

    private final WarrantyClaimRepository claimRepository;
    private final ClaimSettlementRepository settlementRepository;
    private final VendorReimbursementRepository reimbursementRepository;
    private final PartReturnRepository partReturnRepository;
    private final Clock clock;
    private final EntityManager entityManager;
    private final ObjectProvider<OutboxEventWriter> outboxEventWriter;

    /**
     * Load the claim aggregate (header, lines, settlements, reimbursements, part returns) and
     * queue a full snapshot event as part of the current transaction.
     */
    public void publish(@NonNull UUID claimId) {
        OutboxEventWriter writer = outboxEventWriter.getIfAvailable();
        if (writer == null) {
            return;
        }
        // Flush so generated ids, audit timestamps, and the claim @Version carry committed
        // values (pos-invoice pattern) before the snapshot reads them.
        entityManager.flush();
        WarrantyClaim claim = claimRepository.findById(claimId).orElse(null);
        if (claim == null) {
            log.warn("Skipping {} — claim {} not found", WarrantyClaimSnapshotV1.EVENT_TYPE, claimId);
            return;
        }
        WarrantyClaimSnapshotV1 payload = toSnapshot(claim);
        writer.publish(
                DomainTopics.events("warranty"),
                DomainEventEnvelope.of(
                        WarrantyClaimSnapshotV1.EVENT_TYPE,
                        WarrantyClaimSnapshotV1.SCHEMA_VERSION,
                        claim.getId(),
                        claim.getVersion() == null ? 0L : claim.getVersion(),
                        "pos-warranty",
                        null,
                        SecurityContextHelper.getCurrentUsernameOrDefault(SYSTEM),
                        payload,
                        clock));
        log.debug(
                "Queued {} claimId={} status={}", WarrantyClaimSnapshotV1.EVENT_TYPE, claim.getId(), payload.status());
    }

    // ------------------------------------------------------------------ assembly

    private @NonNull WarrantyClaimSnapshotV1 toSnapshot(@NonNull WarrantyClaim claim) {
        UUID claimId = claim.getId();
        return new WarrantyClaimSnapshotV1(
                claimId,
                claim.getClaimCode(),
                claim.getClaimType().name(),
                claim.getStatus().name(),
                claim.getLocationId(),
                claim.getCustomerId(),
                claim.getVehicleId(),
                claim.getVin(),
                claim.getOdometerAtClaim(),
                claim.getOdometerUnit(),
                claim.getOriginWorkorderId(),
                claim.getOriginInvoiceId(),
                claim.getOriginSaleDate(),
                Boolean.TRUE.equals(claim.getOriginUnverified()),
                claim.getProviderId(),
                claim.getPolicyId(),
                claim.getRegistrationId(),
                claim.getFailureDescription(),
                claim.getFailureDate(),
                enumName(claim.getEligibilityResult()),
                enumName(claim.getDecision()),
                claim.getDecisionReason(),
                claim.getDecidedBy(),
                claim.getDecidedAt(),
                Boolean.TRUE.equals(claim.getOverrodeSuggestion()),
                claim.getCreatedAt(),
                claim.getUpdatedAt(),
                claim.getVersion() == null ? 0L : claim.getVersion(),
                claim.getLines().stream().map(ClaimSnapshotPublisher::toLine).toList(),
                settlementRepository.findByClaimId(claimId).stream()
                        .map(ClaimSnapshotPublisher::toSettlement)
                        .toList(),
                reimbursementRepository.findByClaimId(claimId).stream()
                        .map(ClaimSnapshotPublisher::toReimbursement)
                        .toList(),
                partReturnRepository.findByClaimId(claimId).stream()
                        .map(ClaimSnapshotPublisher::toPartReturn)
                        .toList(),
                Instant.now(clock));
    }

    private static WarrantyClaimSnapshotV1.@NonNull Line toLine(@NonNull WarrantyClaimLine line) {
        return new WarrantyClaimSnapshotV1.Line(
                line.getId(),
                line.getSourceType().name(),
                line.getSourceId(),
                line.getSourceLineId(),
                line.getProductEntityId(),
                line.getSku(),
                line.getDescription(),
                line.getSerialNumber(),
                line.getQuantity(),
                line.getOriginalUnitPrice(),
                line.getProrationPct(),
                line.getAmountRequested(),
                line.getAmountApproved(),
                enumName(line.getLineDisposition()));
    }

    private static WarrantyClaimSnapshotV1.@NonNull Settlement toSettlement(@NonNull ClaimSettlement settlement) {
        return new WarrantyClaimSnapshotV1.Settlement(
                settlement.getId(),
                settlement.getSettlementType().name(),
                settlement.getStatus().name(),
                settlement.getCoveredAmount(),
                settlement.getCustomerAmount(),
                settlement.getInvoiceId(),
                settlement.getInvoiceAdjustmentId(),
                settlement.getRefundRecordId(),
                settlement.getReplacementWorkorderId());
    }

    private static WarrantyClaimSnapshotV1.@NonNull Reimbursement toReimbursement(
            @NonNull VendorReimbursement reimbursement) {
        return new WarrantyClaimSnapshotV1.Reimbursement(
                reimbursement.getId(),
                reimbursement.getProviderId(),
                reimbursement.getStatus().name(),
                reimbursement.getAmountRequested(),
                reimbursement.getAmountApproved(),
                reimbursement.getVendorClaimReference());
    }

    private static WarrantyClaimSnapshotV1.@NonNull PartReturn toPartReturn(@NonNull PartReturn partReturn) {
        return new WarrantyClaimSnapshotV1.PartReturn(
                partReturn.getId(),
                partReturn.getClaimLineId(),
                partReturn.getStatus().name(),
                enumName(partReturn.getDisposition()),
                partReturn.getRmaNumber());
    }

    private static @Nullable String enumName(@Nullable Enum<?> value) {
        return value == null ? null : value.name();
    }
}
