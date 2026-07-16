package com.positivity.warranty.internal.service;

import com.positivity.domainevents.DomainEventEnvelope;
import com.positivity.domainevents.DomainTopics;
import com.positivity.domainevents.warranty.WarrantyClaimSettledV1;
import com.positivity.security.common.SecurityContextHelper;
import com.positivity.warranty.internal.client.InvoiceClient;
import com.positivity.warranty.internal.client.WorkorderClient;
import com.positivity.warranty.internal.config.OutboxEventWriter;
import com.positivity.warranty.internal.domain.ClaimStateMachine;
import com.positivity.warranty.internal.dto.SettlementCreateRequest;
import com.positivity.warranty.internal.dto.SettlementResponse;
import com.positivity.warranty.internal.entity.ClaimNote;
import com.positivity.warranty.internal.entity.ClaimSettlement;
import com.positivity.warranty.internal.entity.ClaimStatusHistory;
import com.positivity.warranty.internal.entity.WarrantyClaim;
import com.positivity.warranty.internal.enums.ClaimStatus;
import com.positivity.warranty.internal.enums.SettlementStatus;
import com.positivity.warranty.internal.exception.IllegalClaimStateException;
import com.positivity.warranty.internal.exception.WarrantyIntegrationException;
import com.positivity.warranty.internal.exception.WarrantyNotFoundException;
import com.positivity.warranty.internal.exception.WarrantyUnprocessableException;
import com.positivity.warranty.internal.repository.ClaimNoteRepository;
import com.positivity.warranty.internal.repository.ClaimSettlementRepository;
import com.positivity.warranty.internal.repository.ClaimStatusHistoryRepository;
import com.positivity.warranty.internal.repository.WarrantyClaimRepository;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Optional;
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
 * Executes claim settlements (PRD §3.6, §7 step 6). Settle-customer-first (PRD §2): the flow
 * opens as soon as the claim is {@code APPROVED} and never waits on vendor reimbursement or part
 * return. Credit/refund types write through pos-invoice and fail loudly — the settlement row is
 * kept as {@code FAILED} (transaction commits via {@code noRollbackFor}) and the integration
 * error propagates so staff can retry with a fresh settlement. Each successful settlement
 * enqueues {@code warranty.claim.settled} (PRD §9.3) for pos-accounting.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SettlementServiceImpl implements SettlementService {

    static final String CLAIM_NOT_FOUND_CODE = "WARRANTY_CLAIM_NOT_FOUND";
    static final String CLAIM_STATE_CODE = "WARRANTY_SETTLEMENT_CLAIM_STATE";
    static final String WORKORDER_NOT_FOUND_CODE = "WARRANTY_SETTLEMENT_WORKORDER_NOT_FOUND";

    private static final String SYSTEM = "system";

    /** Claim states in which a settlement may execute (first settles, later ones supplement). */
    private static final Set<ClaimStatus> SETTLEABLE_CLAIM_STATES =
            EnumSet.of(ClaimStatus.APPROVED, ClaimStatus.SETTLED);

    private final WarrantyClaimRepository claimRepository;
    private final ClaimSettlementRepository settlementRepository;
    private final ClaimStatusHistoryRepository statusHistoryRepository;
    private final ClaimNoteRepository noteRepository;
    private final InvoiceClient invoiceClient;
    private final WorkorderClient workorderClient;
    private final ClaimSnapshotPublisher claimSnapshotPublisher;
    private final Clock clock;
    private final EntityManager entityManager;
    private final ObjectProvider<OutboxEventWriter> outboxEventWriter;

    @Override
    @NonNull
    @Transactional(noRollbackFor = WarrantyIntegrationException.class)
    public SettlementResponse create(@NonNull UUID claimId, @NonNull SettlementCreateRequest request) {
        WarrantyClaim claim = loadClaim(claimId);
        if (!SETTLEABLE_CLAIM_STATES.contains(claim.getStatus())) {
            throw new IllegalClaimStateException(
                    CLAIM_STATE_CODE,
                    "Claim " + claim.getClaimCode() + " is " + claim.getStatus()
                            + "; settlements can only be executed on an APPROVED or SETTLED claim",
                    "Approve the claim (POST /v1/warranty/claims/{id}/decision) before settling the customer.");
        }

        ClaimSettlement settlement = ClaimSettlement.builder()
                .claimId(claimId)
                .settlementType(request.settlementType())
                .status(SettlementStatus.PENDING)
                .coveredAmount(request.coveredAmount())
                .customerAmount(request.customerAmount())
                .build();

        switch (request.settlementType()) {
            case REPLACEMENT_WORKORDER -> linkReplacementWorkorder(settlement, request);
            case INVOICE_CREDIT, PRORATED_CREDIT, GOODWILL -> executeInvoiceAdjustment(claim, settlement, request);
            case REFUND -> executeRefund(claim, settlement, request);
            case NO_ACTION -> settlement.setStatus(SettlementStatus.COMPLETED);
        }

        // The FAILED path throws out of the helpers above (settlement already persisted as
        // FAILED); from here on the settlement succeeded.
        ClaimSettlement saved = settlementRepository.save(settlement);
        recordNote(claimId, request);
        settleClaimIfFirst(claim, saved);
        publishSettled(claim, saved);
        claimSnapshotPublisher.publish(claimId);
        return SettlementResponse.from(saved, claim.getStatus());
    }

    // ------------------------------------------------------------------ per-type execution

    /**
     * Link-only (PRD §3.6): staff created the replacement workorder through the normal flow;
     * warranty validates it exists in pos-workorder and stores the reference. No write leaves
     * this module — pos-workorder stays unaware of warranty.
     */
    private void linkReplacementWorkorder(
            @NonNull ClaimSettlement settlement, @NonNull SettlementCreateRequest request) {
        UUID workorderId = require(
                request.replacementWorkorderId(),
                "replacementWorkorderId is required for REPLACEMENT_WORKORDER settlements");
        workorderClient
                .getWorkorderDetail(workorderId)
                .orElseThrow(() -> new WarrantyUnprocessableException(
                        WORKORDER_NOT_FOUND_CODE,
                        "Replacement workorder '" + workorderId + "' could not be resolved in pos-workorder"));
        settlement.setReplacementWorkorderId(workorderId);
        settlement.setStatus(SettlementStatus.COMPLETED);
    }

    private void executeInvoiceAdjustment(
            @NonNull WarrantyClaim claim,
            @NonNull ClaimSettlement settlement,
            @NonNull SettlementCreateRequest request) {
        UUID invoiceId =
                require(request.invoiceId(), "invoiceId is required for " + request.settlementType() + " settlements");
        settlement.setInvoiceId(invoiceId);
        // Persist PENDING first so the FAILED outcome survives the rethrow (noRollbackFor).
        settlementRepository.save(settlement);
        try {
            Optional<UUID> adjustmentId = invoiceClient.createAdjustment(
                    invoiceId,
                    request.coveredAmount(),
                    "Warranty claim " + claim.getClaimCode() + " " + request.settlementType() + " settlement",
                    currentActor(),
                    claim.getClaimCode());
            settlement.setInvoiceAdjustmentId(adjustmentId.orElse(null));
            settlement.setStatus(SettlementStatus.COMPLETED);
        } catch (WarrantyIntegrationException ex) {
            markFailed(settlement, ex);
            throw ex;
        }
    }

    private void executeRefund(
            @NonNull WarrantyClaim claim,
            @NonNull ClaimSettlement settlement,
            @NonNull SettlementCreateRequest request) {
        UUID invoiceId = require(request.invoiceId(), "invoiceId is required for REFUND settlements");
        UUID paymentId = require(request.paymentId(), "paymentId is required for REFUND settlements");
        settlement.setInvoiceId(invoiceId);
        settlementRepository.save(settlement);
        try {
            Optional<UUID> refundId = invoiceClient.createRefund(
                    invoiceId, paymentId, request.coveredAmount(), request.notes(), claim.getClaimCode());
            settlement.setRefundRecordId(refundId.orElse(null));
            settlement.setStatus(SettlementStatus.COMPLETED);
        } catch (WarrantyIntegrationException ex) {
            markFailed(settlement, ex);
            throw ex;
        }
    }

    private void markFailed(@NonNull ClaimSettlement settlement, @NonNull WarrantyIntegrationException cause) {
        settlement.setStatus(SettlementStatus.FAILED);
        settlementRepository.save(settlement);
        log.warn(
                "Settlement {} for claim {} FAILED: {}",
                settlement.getSettlementType(),
                settlement.getClaimId(),
                cause.getMessage());
    }

    // ------------------------------------------------------------------ claim transition + audit

    /**
     * First successful settlement moves the claim {@code APPROVED → SETTLED} through the state
     * machine with a status-history row (same audit path as {@code ClaimServiceImpl}); additional
     * settlements on a SETTLED claim leave the status alone.
     */
    private void settleClaimIfFirst(@NonNull WarrantyClaim claim, @NonNull ClaimSettlement settlement) {
        if (claim.getStatus() != ClaimStatus.APPROVED) {
            return;
        }
        ClaimStatus from = claim.getStatus();
        ClaimStateMachine.assertTransition(from, ClaimStatus.SETTLED);
        claim.setStatus(ClaimStatus.SETTLED);
        statusHistoryRepository.save(ClaimStatusHistory.builder()
                .claimId(claim.getId())
                .fromStatus(from)
                .toStatus(ClaimStatus.SETTLED)
                .actor(currentActor())
                .reason("customer settled (" + settlement.getSettlementType() + ")")
                .build());
        claimRepository.save(claim);
    }

    private void recordNote(@NonNull UUID claimId, @NonNull SettlementCreateRequest request) {
        String notes = request.notes();
        if (notes == null || notes.isBlank()) {
            return;
        }
        noteRepository.save(ClaimNote.builder()
                .claimId(claimId)
                .note("settlement " + request.settlementType() + ": " + notes.trim())
                .build());
    }

    // ------------------------------------------------------------------ outbox

    private void publishSettled(@NonNull WarrantyClaim claim, @NonNull ClaimSettlement settlement) {
        OutboxEventWriter writer = outboxEventWriter.getIfAvailable();
        if (writer == null) {
            return;
        }
        // Flush so generated ids and the claim @Version carry committed values (pos-invoice
        // pattern) before the envelope snapshots them.
        entityManager.flush();
        WarrantyClaimSettledV1 payload = new WarrantyClaimSettledV1(
                claim.getId(),
                claim.getClaimCode(),
                claim.getCustomerId(),
                claim.getLocationId(),
                settlement.getId(),
                settlement.getSettlementType().name(),
                settlement.getCoveredAmount(),
                settlement.getCustomerAmount(),
                settlement.getInvoiceId(),
                settlement.getRefundRecordId(),
                settlement.getReplacementWorkorderId(),
                Instant.now(clock));
        writer.publish(
                DomainTopics.events("warranty"),
                DomainEventEnvelope.of(
                        WarrantyClaimSettledV1.EVENT_TYPE,
                        WarrantyClaimSettledV1.SCHEMA_VERSION,
                        claim.getId(),
                        claim.getVersion() == null ? 0L : claim.getVersion(),
                        "pos-warranty",
                        null,
                        currentActor(),
                        payload,
                        clock));
        log.debug(
                "Queued {} claimId={} settlementId={}",
                WarrantyClaimSettledV1.EVENT_TYPE,
                claim.getId(),
                settlement.getId());
    }

    // ------------------------------------------------------------------ internals

    private @NonNull WarrantyClaim loadClaim(@NonNull UUID claimId) {
        return claimRepository
                .findById(claimId)
                .orElseThrow(() ->
                        new WarrantyNotFoundException(CLAIM_NOT_FOUND_CODE, "Warranty claim not found: " + claimId));
    }

    @NonNull
    private static UUID require(@Nullable UUID value, @NonNull String message) {
        if (value == null) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }

    @NonNull
    private static String currentActor() {
        return SecurityContextHelper.getCurrentUsernameOrDefault(SYSTEM);
    }
}
