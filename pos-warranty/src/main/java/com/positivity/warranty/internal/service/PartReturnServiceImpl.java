package com.positivity.warranty.internal.service;

import com.positivity.domainevents.DomainEventEnvelope;
import com.positivity.domainevents.DomainTopics;
import com.positivity.domainevents.warranty.WarrantyPartReturnRequestedV1;
import com.positivity.domainevents.warranty.WarrantyPartReturnShippedV1;
import com.positivity.security.common.SecurityContextHelper;
import com.positivity.warranty.internal.config.OutboxEventWriter;
import com.positivity.warranty.internal.dto.PartReturnCreateRequest;
import com.positivity.warranty.internal.dto.PartReturnResponse;
import com.positivity.warranty.internal.dto.PartReturnUpdateRequest;
import com.positivity.warranty.internal.entity.PartReturn;
import com.positivity.warranty.internal.entity.WarrantyClaim;
import com.positivity.warranty.internal.entity.WarrantyClaimLine;
import com.positivity.warranty.internal.enums.ClaimStatus;
import com.positivity.warranty.internal.enums.PartReturnDisposition;
import com.positivity.warranty.internal.enums.PartReturnStatus;
import com.positivity.warranty.internal.exception.IllegalClaimStateException;
import com.positivity.warranty.internal.exception.WarrantyNotFoundException;
import com.positivity.warranty.internal.repository.PartReturnRepository;
import com.positivity.warranty.internal.repository.WarrantyClaimRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Defective-part RMA child lifecycle (PRD §3.8). Created when the governing policy has
 * {@code requiresPartReturn} or staff choose to return/hold the part — staff choice is always
 * permitted, so no policy check gates creation. Emits {@code warranty.part-return.*} outbox
 * events (PRD §9.3) so pos-inventory can quarantine the defective unit in a follow-up.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PartReturnServiceImpl implements PartReturnService {

    static final String CLAIM_NOT_FOUND_CODE = "WARRANTY_CLAIM_NOT_FOUND";
    static final String LINE_NOT_FOUND_CODE = "WARRANTY_CLAIM_LINE_NOT_FOUND";
    static final String PART_RETURN_NOT_FOUND_CODE = "WARRANTY_PART_RETURN_NOT_FOUND";
    static final String ALREADY_EXISTS_CODE = "WARRANTY_PART_RETURN_EXISTS";
    static final String CLAIM_STATE_CODE = "WARRANTY_PART_RETURN_CLAIM_STATE";
    static final String ILLEGAL_TRANSITION_CODE = "WARRANTY_PART_RETURN_ILLEGAL_TRANSITION";
    static final String TERMINAL_CODE = "WARRANTY_PART_RETURN_TERMINAL";
    static final String DISPOSITION_CODE = "WARRANTY_PART_RETURN_DISPOSITION_MISMATCH";

    private static final String SYSTEM = "system";

    /** Claims done with the customer journey no longer accept new RMAs. */
    private static final Set<ClaimStatus> BLOCKED_CLAIM_STATES = EnumSet.of(ClaimStatus.CANCELLED, ClaimStatus.CLOSED);

    /**
     * Child state machine (PRD §3.8): AWAITING_PART → ON_HOLD → SHIPPED → RECEIVED_BY_VENDOR, or
     * to SCRAPPED/CLOSED per disposition. ON_HOLD may be skipped (part shipped straight out);
     * RECEIVED_BY_VENDOR, SCRAPPED, and CLOSED are terminal (they unblock claim CLOSE).
     */
    private static final Map<PartReturnStatus, Set<PartReturnStatus>> LEGAL_MOVES = new EnumMap<>(Map.of(
            PartReturnStatus.AWAITING_PART,
            EnumSet.of(
                    PartReturnStatus.ON_HOLD,
                    PartReturnStatus.SHIPPED,
                    PartReturnStatus.SCRAPPED,
                    PartReturnStatus.CLOSED),
            PartReturnStatus.ON_HOLD,
            EnumSet.of(PartReturnStatus.SHIPPED, PartReturnStatus.SCRAPPED, PartReturnStatus.CLOSED),
            PartReturnStatus.SHIPPED,
            EnumSet.of(PartReturnStatus.RECEIVED_BY_VENDOR, PartReturnStatus.CLOSED),
            PartReturnStatus.RECEIVED_BY_VENDOR,
            EnumSet.noneOf(PartReturnStatus.class),
            PartReturnStatus.SCRAPPED,
            EnumSet.noneOf(PartReturnStatus.class),
            PartReturnStatus.CLOSED,
            EnumSet.noneOf(PartReturnStatus.class)));

    private final WarrantyClaimRepository claimRepository;
    private final PartReturnRepository partReturnRepository;
    private final ClaimSnapshotPublisher claimSnapshotPublisher;
    private final Clock clock;
    private final EntityManager entityManager;
    private final ObjectProvider<OutboxEventWriter> outboxEventWriter;

    @Override
    @NonNull
    @Transactional
    public PartReturnResponse create(@NonNull UUID claimId, @NonNull PartReturnCreateRequest request) {
        WarrantyClaim claim = loadClaim(claimId);
        if (BLOCKED_CLAIM_STATES.contains(claim.getStatus())) {
            throw new IllegalClaimStateException(
                    CLAIM_STATE_CODE,
                    "Claim " + claim.getClaimCode() + " is " + claim.getStatus()
                            + "; part returns cannot be opened on a terminal claim",
                    "Open part returns before the claim is closed or cancelled.");
        }
        WarrantyClaimLine line = claim.getLines().stream()
                .filter(l -> request.claimLineId().equals(l.getId()))
                .findFirst()
                .orElseThrow(() -> new WarrantyNotFoundException(
                        LINE_NOT_FOUND_CODE,
                        "Claim line " + request.claimLineId() + " does not belong to claim " + claim.getClaimCode()));
        partReturnRepository.findByClaimLineId(request.claimLineId()).ifPresent(existing -> {
            throw new IllegalClaimStateException(
                    ALREADY_EXISTS_CODE,
                    "Claim line " + request.claimLineId() + " already has part return " + existing.getId()
                            + " (at most one per line)",
                    "Update the existing part return via PUT /v1/warranty/part-returns/" + existing.getId() + ".");
        });

        bumpClaimAggregateVersion(claim);
        PartReturn partReturn = PartReturn.builder()
                .claimId(claimId)
                .claimLineId(request.claimLineId())
                .disposition(request.disposition())
                .status(PartReturnStatus.AWAITING_PART)
                .rmaNumber(request.rmaNumber())
                .holdLocationNote(request.holdLocationNote())
                .build();
        PartReturn saved;
        try {
            // Flush inside the try so the UNIQUE(claim_line_id) constraint fires here: a
            // concurrent create that lost the check-then-insert race gets the same 409 as a
            // sequential retry (mirrors ReimbursementServiceImpl.submit).
            saved = partReturnRepository.saveAndFlush(partReturn);
        } catch (DataIntegrityViolationException ex) {
            throw new IllegalClaimStateException(
                    ALREADY_EXISTS_CODE,
                    "Claim line " + request.claimLineId() + " already has a part return (at most one per line)",
                    "Update the existing part return via PUT /v1/warranty/part-returns/{id}.");
        }

        publishRequested(claim, line, saved);
        claimSnapshotPublisher.publish(claimId);
        return PartReturnResponse.from(saved);
    }

    @Override
    @NonNull
    @Transactional
    public PartReturnResponse update(@NonNull UUID partReturnId, @NonNull PartReturnUpdateRequest request) {
        PartReturn partReturn = partReturnRepository
                .findById(partReturnId)
                .orElseThrow(() -> new WarrantyNotFoundException(
                        PART_RETURN_NOT_FOUND_CODE, "Part return not found: " + partReturnId));
        PartReturnStatus current = partReturn.getStatus();
        if (LEGAL_MOVES.getOrDefault(current, Set.of()).isEmpty()) {
            throw new IllegalClaimStateException(
                    TERMINAL_CODE,
                    "Part return " + partReturnId + " is terminal (" + current + ") and can no longer be changed",
                    "No further transitions are possible from " + current + ".");
        }

        PartReturnDisposition effectiveDisposition =
                request.disposition() != null ? request.disposition() : partReturn.getDisposition();
        boolean enteredShipped = false;
        Instant now = Instant.now(clock);

        if (request.status() != null && request.status() != current) {
            PartReturnStatus target = request.status();
            if (!LEGAL_MOVES.getOrDefault(current, Set.of()).contains(target)) {
                throw new IllegalClaimStateException(
                        ILLEGAL_TRANSITION_CODE,
                        "Part return " + partReturnId + " cannot move " + current + " -> " + target,
                        "Legal transitions from " + current + ": " + LEGAL_MOVES.getOrDefault(current, Set.of()) + ".");
            }
            requireDispositionFor(target, effectiveDisposition, partReturnId);
            partReturn.setStatus(target);
            if (target == PartReturnStatus.SHIPPED) {
                enteredShipped = true;
                partReturn.setShippedAt(request.shippedAt() != null ? request.shippedAt() : now);
            }
        }

        if (request.disposition() != null) {
            partReturn.setDisposition(request.disposition());
        }
        if (request.carrier() != null) {
            partReturn.setCarrier(request.carrier());
        }
        if (request.trackingNumber() != null) {
            partReturn.setTrackingNumber(request.trackingNumber());
        }
        if (request.rmaNumber() != null) {
            partReturn.setRmaNumber(request.rmaNumber());
        }
        if (request.holdLocationNote() != null) {
            partReturn.setHoldLocationNote(request.holdLocationNote());
        }
        if (request.shippedAt() != null && !enteredShipped && partReturn.getShippedAt() != null) {
            // Correcting the recorded ship time on an already-shipped return.
            partReturn.setShippedAt(request.shippedAt());
        }
        bumpClaimAggregateVersion(loadClaim(partReturn.getClaimId()));
        PartReturn saved = partReturnRepository.save(partReturn);

        if (enteredShipped) {
            publishShipped(saved);
        }
        claimSnapshotPublisher.publish(saved.getClaimId());
        return PartReturnResponse.from(saved);
    }

    @Override
    @NonNull
    @Transactional(readOnly = true)
    public List<PartReturnResponse> worklist(@Nullable PartReturnStatus status) {
        List<PartReturn> results =
                status != null ? partReturnRepository.findByStatus(status) : partReturnRepository.findAll();
        return results.stream().map(PartReturnResponse::from).toList();
    }

    // ------------------------------------------------------------------ internals

    /** "Per disposition" gates (PRD §3.8): ship/receive needs RETURN_TO_VENDOR; scrap needs SCRAP_AUTHORIZED. */
    private static void requireDispositionFor(
            PartReturnStatus target, PartReturnDisposition disposition, UUID partReturnId) {
        if ((target == PartReturnStatus.SHIPPED || target == PartReturnStatus.RECEIVED_BY_VENDOR)
                && disposition != PartReturnDisposition.RETURN_TO_VENDOR) {
            throw new IllegalClaimStateException(
                    DISPOSITION_CODE,
                    "Part return " + partReturnId + " cannot move to " + target + " with disposition " + disposition,
                    "Set disposition RETURN_TO_VENDOR (in this request or beforehand) to ship the part to the"
                            + " vendor, or close/scrap it per its disposition.");
        }
        if (target == PartReturnStatus.SCRAPPED && disposition != PartReturnDisposition.SCRAP_AUTHORIZED) {
            throw new IllegalClaimStateException(
                    DISPOSITION_CODE,
                    "Part return " + partReturnId + " cannot be SCRAPPED with disposition " + disposition,
                    "Set disposition SCRAP_AUTHORIZED (in this request or beforehand) before scrapping the part.");
        }
    }

    private @NonNull WarrantyClaim loadClaim(@NonNull UUID claimId) {
        return claimRepository
                .findById(claimId)
                .orElseThrow(() ->
                        new WarrantyNotFoundException(CLAIM_NOT_FOUND_CODE, "Warranty claim not found: " + claimId));
    }

    /**
     * A part-return mutation changes the claim aggregate's snapshot content without dirtying
     * the claim row, so force a claim {@code @Version} increment: the
     * {@code warranty.claim.snapshot} envelope's {@code aggregateVersion} must be strictly
     * monotonic across every aggregate mutation (consumers dedupe/order by it). Pessimistic
     * force-increment bumps the version immediately (visible to the snapshot flush) and
     * serializes concurrent child mutations against claim-header changes.
     */
    private void bumpClaimAggregateVersion(@NonNull WarrantyClaim claim) {
        entityManager.lock(claim, LockModeType.PESSIMISTIC_FORCE_INCREMENT);
    }

    private void publishRequested(WarrantyClaim claim, WarrantyClaimLine line, PartReturn partReturn) {
        OutboxEventWriter writer = outboxEventWriter.getIfAvailable();
        if (writer == null) {
            return;
        }
        // Flush so generated ids and the claim @Version carry committed values (pos-invoice
        // pattern) before the envelope snapshots them.
        entityManager.flush();
        WarrantyPartReturnRequestedV1 payload = new WarrantyPartReturnRequestedV1(
                claim.getId(),
                partReturn.getClaimLineId(),
                partReturn.getId(),
                line.getProductEntityId(),
                line.getSerialNumber(),
                partReturn.getDisposition().name(),
                Instant.now(clock));
        writer.publish(
                DomainTopics.events("warranty"),
                envelope(
                        WarrantyPartReturnRequestedV1.EVENT_TYPE,
                        WarrantyPartReturnRequestedV1.SCHEMA_VERSION,
                        claim,
                        payload));
        log.debug("Queued {} claimId={}", WarrantyPartReturnRequestedV1.EVENT_TYPE, claim.getId());
    }

    private void publishShipped(PartReturn partReturn) {
        OutboxEventWriter writer = outboxEventWriter.getIfAvailable();
        if (writer == null) {
            return;
        }
        entityManager.flush();
        WarrantyClaim claim = loadClaim(partReturn.getClaimId());
        WarrantyPartReturnShippedV1 payload = new WarrantyPartReturnShippedV1(
                partReturn.getClaimId(),
                partReturn.getClaimLineId(),
                partReturn.getId(),
                partReturn.getCarrier(),
                partReturn.getTrackingNumber(),
                partReturn.getShippedAt());
        writer.publish(
                DomainTopics.events("warranty"),
                envelope(
                        WarrantyPartReturnShippedV1.EVENT_TYPE,
                        WarrantyPartReturnShippedV1.SCHEMA_VERSION,
                        claim,
                        payload));
        log.debug("Queued {} claimId={}", WarrantyPartReturnShippedV1.EVENT_TYPE, partReturn.getClaimId());
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
