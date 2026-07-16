package com.positivity.warranty.internal.service;

import com.positivity.security.common.SecurityContextHelper;
import com.positivity.warranty.internal.client.VehicleInventoryClient;
import com.positivity.warranty.internal.domain.ClaimStateMachine;
import com.positivity.warranty.internal.dto.ClaimActionRequest;
import com.positivity.warranty.internal.dto.ClaimCreateRequest;
import com.positivity.warranty.internal.dto.ClaimDecisionRequest;
import com.positivity.warranty.internal.dto.ClaimLineRequest;
import com.positivity.warranty.internal.dto.ClaimLineResponse;
import com.positivity.warranty.internal.dto.ClaimNoteRequest;
import com.positivity.warranty.internal.dto.ClaimResponse;
import com.positivity.warranty.internal.dto.ClaimSummaryResponse;
import com.positivity.warranty.internal.dto.ClaimUpdateRequest;
import com.positivity.warranty.internal.entity.ClaimNote;
import com.positivity.warranty.internal.entity.ClaimStatusHistory;
import com.positivity.warranty.internal.entity.WarrantyClaim;
import com.positivity.warranty.internal.entity.WarrantyClaimLine;
import com.positivity.warranty.internal.enums.ClaimDecision;
import com.positivity.warranty.internal.enums.ClaimStatus;
import com.positivity.warranty.internal.enums.EligibilityResult;
import com.positivity.warranty.internal.enums.LineDisposition;
import com.positivity.warranty.internal.enums.PartReturnStatus;
import com.positivity.warranty.internal.enums.ReimbursementStatus;
import com.positivity.warranty.internal.exception.IllegalClaimStateException;
import com.positivity.warranty.internal.exception.WarrantyNotFoundException;
import com.positivity.warranty.internal.repository.ClaimNoteRepository;
import com.positivity.warranty.internal.repository.ClaimSettlementRepository;
import com.positivity.warranty.internal.repository.ClaimStatusHistoryRepository;
import com.positivity.warranty.internal.repository.PartReturnRepository;
import com.positivity.warranty.internal.repository.VendorReimbursementRepository;
import com.positivity.warranty.internal.repository.WarrantyClaimRepository;
import com.positivity.warranty.internal.repository.WarrantyPolicyRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ClaimServiceImpl implements ClaimService {

    static final String NOT_FOUND_CODE = "WARRANTY_CLAIM_NOT_FOUND";
    static final String LINE_NOT_FOUND_CODE = "WARRANTY_CLAIM_LINE_NOT_FOUND";
    static final String PHOTO_NOT_FOUND_CODE = "WARRANTY_CLAIM_PHOTO_NOT_FOUND";
    static final String CLOSE_BLOCKED_CODE = "WARRANTY_CLAIM_CLOSE_BLOCKED";

    private static final String DEFAULT_ACTOR = "system";

    /** Terminal reimbursement states that no longer block CLOSE (PRD §5). */
    private static final Set<ReimbursementStatus> TERMINAL_REIMBURSEMENT = EnumSet.of(
            ReimbursementStatus.CREDIT_RECEIVED,
            ReimbursementStatus.WRITTEN_OFF,
            ReimbursementStatus.NOT_APPLICABLE,
            ReimbursementStatus.DENIED);

    /** Terminal RMA states that no longer block CLOSE (PRD §3.8, §5). */
    private static final Set<PartReturnStatus> TERMINAL_PART_RETURN =
            EnumSet.of(PartReturnStatus.RECEIVED_BY_VENDOR, PartReturnStatus.SCRAPPED, PartReturnStatus.CLOSED);

    /**
     * States in which the eligibility suggestion may be recomputed. Once a human decision has
     * been made (APPROVED/DENIED/SETTLED) the persisted proration inputs and coverage selection
     * are frozen audit evidence (PRD §6) — a DENIED claim regains recompute via appeal →
     * IN_REVIEW.
     */
    private static final Set<ClaimStatus> ELIGIBILITY_RECOMPUTABLE =
            EnumSet.of(ClaimStatus.DRAFT, ClaimStatus.SUBMITTED, ClaimStatus.IN_REVIEW, ClaimStatus.INFO_NEEDED);

    private final WarrantyClaimRepository claimRepository;
    private final WarrantyPolicyRepository policyRepository;
    private final ClaimStatusHistoryRepository statusHistoryRepository;
    private final ClaimNoteRepository noteRepository;
    private final ClaimSettlementRepository settlementRepository;
    private final VendorReimbursementRepository reimbursementRepository;
    private final PartReturnRepository partReturnRepository;
    private final ClaimCodeService claimCodeService;
    private final EligibilityService eligibilityService;
    private final VehicleInventoryClient vehicleInventoryClient;
    private final ClaimSnapshotPublisher claimSnapshotPublisher;
    private final Clock clock;

    // ------------------------------------------------------------------ intake

    @Override
    @NonNull
    @Transactional
    public ClaimResponse create(@NonNull ClaimCreateRequest request) {
        WarrantyClaim claim = WarrantyClaim.builder()
                .claimCode(claimCodeService.nextClaimCode())
                .claimType(request.claimType())
                .status(ClaimStatus.DRAFT)
                .locationId(request.locationId())
                .customerId(request.customerId())
                .vehicleId(request.vehicleId())
                .originWorkorderId(request.originWorkorderId())
                .originInvoiceId(request.originInvoiceId())
                .originSaleDate(request.originSaleDate())
                .originUnverified(request.originWorkorderId() == null && request.originInvoiceId() == null)
                .registrationId(request.registrationId())
                .failureDescription(request.failureDescription())
                .failureDate(request.failureDate())
                .photoEvidenceUrls(
                        request.photoEvidenceUrls() == null ? null : List.copyOf(request.photoEvidenceUrls()))
                .build();

        // Vehicle snapshot (PRD §3.4): VIN + odometer read from pos-vehicle-inventory and frozen.
        // The read client degrades to empty on outage; the claim is still created (walk-in
        // resilience) and the snapshot stays null.
        vehicleInventoryClient.getVehicle(request.vehicleId()).ifPresent(snapshot -> {
            claim.setVin(snapshot.vin());
            claim.setOdometerAtClaim(
                    snapshot.odometerValue() == null
                            ? null
                            : snapshot.odometerValue().longValue());
            claim.setOdometerUnit(snapshot.odometerUnit());
        });

        if (request.lines() != null) {
            request.lines().forEach(line -> claim.addLine(toLineEntity(line)));
        }

        WarrantyClaim saved = claimRepository.save(claim);
        recordHistory(saved.getId(), null, ClaimStatus.DRAFT, "claim created");
        claimSnapshotPublisher.publish(saved.getId());
        return toDetail(saved);
    }

    @Override
    @NonNull
    @Transactional(readOnly = true)
    public ClaimResponse getById(@NonNull UUID id) {
        return toDetail(load(id));
    }

    @Override
    @NonNull
    @Transactional(readOnly = true)
    public Page<ClaimSummaryResponse> search(
            @Nullable UUID customerId,
            @Nullable UUID vehicleId,
            @Nullable ClaimStatus status,
            @Nullable String claimCode,
            @Nullable UUID locationId,
            @NonNull Pageable pageable) {
        if (claimCode != null && !claimCode.isBlank()) {
            List<ClaimSummaryResponse> match = claimRepository
                    .findByClaimCode(claimCode.trim())
                    .filter(c -> (customerId == null || customerId.equals(c.getCustomerId()))
                            && (vehicleId == null || vehicleId.equals(c.getVehicleId()))
                            && (status == null || status == c.getStatus())
                            && (locationId == null || locationId.equals(c.getLocationId())))
                    .map(ClaimServiceImpl::toSummary)
                    .map(List::of)
                    .orElse(List.of());
            return new PageImpl<>(match, pageable, match.size());
        }
        return claimRepository
                .search(customerId, vehicleId, status, locationId, pageable)
                .map(ClaimServiceImpl::toSummary);
    }

    // ------------------------------------------------------------------ edits (DRAFT / INFO_NEEDED)

    @Override
    @NonNull
    @Transactional
    public ClaimResponse update(@NonNull UUID id, @NonNull ClaimUpdateRequest request) {
        WarrantyClaim claim = loadEditable(id);
        if (request.claimType() != null) {
            claim.setClaimType(request.claimType());
        }
        claim.setLocationId(request.locationId());
        claim.setOriginWorkorderId(request.originWorkorderId());
        claim.setOriginInvoiceId(request.originInvoiceId());
        claim.setOriginSaleDate(request.originSaleDate());
        claim.setOriginUnverified(request.originWorkorderId() == null && request.originInvoiceId() == null);
        claim.setRegistrationId(request.registrationId());
        claim.setFailureDescription(request.failureDescription());
        claim.setFailureDate(request.failureDate());
        if (request.photoEvidenceUrls() != null) {
            claim.setPhotoEvidenceUrls(List.copyOf(request.photoEvidenceUrls()));
        }
        WarrantyClaim saved = claimRepository.save(claim);
        claimSnapshotPublisher.publish(id);
        return toDetail(saved);
    }

    @Override
    @NonNull
    @Transactional
    public ClaimResponse addLine(@NonNull UUID id, @NonNull ClaimLineRequest request) {
        WarrantyClaim claim = loadEditable(id);
        claim.addLine(toLineEntity(request));
        WarrantyClaim saved = claimRepository.save(claim);
        claimSnapshotPublisher.publish(id);
        return toDetail(saved);
    }

    @Override
    @NonNull
    @Transactional
    public ClaimResponse removeLine(@NonNull UUID id, @NonNull UUID lineId) {
        WarrantyClaim claim = loadEditable(id);
        WarrantyClaimLine line = claim.getLines().stream()
                .filter(l -> lineId.equals(l.getId()))
                .findFirst()
                .orElseThrow(() -> new WarrantyNotFoundException(
                        LINE_NOT_FOUND_CODE, "Claim line '" + lineId + "' was not found on claim '" + id + "'"));
        claim.removeLine(line);
        WarrantyClaim saved = claimRepository.save(claim);
        claimSnapshotPublisher.publish(id);
        return toDetail(saved);
    }

    @Override
    @NonNull
    @Transactional
    public ClaimResponse addPhoto(@NonNull UUID id, @NonNull String url) {
        WarrantyClaim claim = loadEditable(id);
        List<String> photos = claim.getPhotoEvidenceUrls() == null
                ? new ArrayList<>()
                : new ArrayList<>(claim.getPhotoEvidenceUrls());
        if (!photos.contains(url)) {
            photos.add(url);
        }
        claim.setPhotoEvidenceUrls(photos);
        WarrantyClaim saved = claimRepository.save(claim);
        claimSnapshotPublisher.publish(id);
        return toDetail(saved);
    }

    @Override
    @NonNull
    @Transactional
    public ClaimResponse removePhoto(@NonNull UUID id, @NonNull String url) {
        WarrantyClaim claim = loadEditable(id);
        List<String> photos = claim.getPhotoEvidenceUrls() == null
                ? new ArrayList<>()
                : new ArrayList<>(claim.getPhotoEvidenceUrls());
        if (!photos.remove(url)) {
            throw new WarrantyNotFoundException(
                    PHOTO_NOT_FOUND_CODE, "Photo URL is not attached to claim '" + id + "'");
        }
        claim.setPhotoEvidenceUrls(photos);
        WarrantyClaim saved = claimRepository.save(claim);
        claimSnapshotPublisher.publish(id);
        return toDetail(saved);
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    @NonNull
    @Transactional
    public ClaimResponse submit(@NonNull UUID id) {
        WarrantyClaim claim = load(id);
        ClaimStatus from = claim.getStatus();
        ClaimStatus to =
                switch (from) {
                    case DRAFT -> ClaimStatus.SUBMITTED;
                    case INFO_NEEDED -> ClaimStatus.IN_REVIEW;
                    default -> throw ClaimStateMachine.illegalTransition(from, "submit");
                };

        if (claim.getLines().isEmpty()) {
            throw new IllegalArgumentException("A claim must have at least one claim line before it can be submitted");
        }

        // Eligibility runs automatically at submit (PRD §6) and persists results + policy
        // selection onto the claim inside this transaction.
        eligibilityService.evaluate(claim.getId());
        claim = load(id);

        enforcePhotoEvidence(claim);

        transition(claim, to, from == ClaimStatus.INFO_NEEDED ? "info provided" : "claim submitted");
        WarrantyClaim saved = claimRepository.save(claim);
        claimSnapshotPublisher.publish(id);
        return toDetail(saved);
    }

    @Override
    @NonNull
    @Transactional
    public ClaimResponse evaluateEligibility(@NonNull UUID id) {
        WarrantyClaim claim = load(id);
        if (!ELIGIBILITY_RECOMPUTABLE.contains(claim.getStatus())) {
            throw ClaimStateMachine.illegalTransition(claim.getStatus(), "re-run eligibility");
        }
        eligibilityService.evaluate(claim.getId());
        claimSnapshotPublisher.publish(id);
        return toDetail(load(id));
    }

    @Override
    @NonNull
    @Transactional
    public ClaimResponse decide(@NonNull UUID id, @NonNull ClaimDecisionRequest request) {
        WarrantyClaim claim = load(id);
        String reason = trimToNull(request.reason());

        if (request.decision() == ClaimDecisionRequest.Action.APPEAL) {
            return appeal(claim, reason);
        }

        // First decision touch on a SUBMITTED claim implicitly begins review (PRD §5).
        if (claim.getStatus() == ClaimStatus.SUBMITTED) {
            transition(claim, ClaimStatus.IN_REVIEW, "review started");
        }

        ClaimStatus target =
                switch (request.decision()) {
                    case APPROVE -> ClaimStatus.APPROVED;
                    case DENY -> ClaimStatus.DENIED;
                    case REQUEST_INFO -> ClaimStatus.INFO_NEEDED;
                    case APPEAL -> throw new IllegalStateException("unreachable");
                };
        if (request.decision() == ClaimDecisionRequest.Action.DENY && reason == null) {
            throw new IllegalArgumentException("Denying a claim requires a reason");
        }
        boolean overrode = contradictsSuggestion(claim, request.decision());
        if (overrode && reason == null) {
            throw new IllegalArgumentException("This decision contradicts the computed suggestion ("
                    + claim.getEligibilityResult() + "); an override reason is required");
        }
        boolean lineOverride = applyLineDecisions(claim, request);

        transition(claim, target, reason);
        claim.setDecision(toClaimDecision(request.decision()));
        claim.setDecisionReason(reason);
        claim.setDecidedBy(currentActor());
        claim.setDecidedAt(Instant.now(clock));
        claim.setOverrodeSuggestion(overrode || lineOverride);
        WarrantyClaim saved = claimRepository.save(claim);
        claimSnapshotPublisher.publish(id);
        return toDetail(saved);
    }

    @Override
    @NonNull
    @Transactional
    public ClaimResponse cancel(@NonNull UUID id, @NonNull ClaimActionRequest request) {
        WarrantyClaim claim = load(id);
        transition(claim, ClaimStatus.CANCELLED, trimToNull(request.reason()));
        WarrantyClaim saved = claimRepository.save(claim);
        claimSnapshotPublisher.publish(id);
        return toDetail(saved);
    }

    @Override
    @NonNull
    @Transactional
    public ClaimResponse close(@NonNull UUID id, @NonNull ClaimActionRequest request) {
        WarrantyClaim claim = load(id);
        // Validate the move first so terminal/foreign states get the state-machine error,
        // then enforce the child-lifecycle gate (PRD §5).
        ClaimStateMachine.assertTransition(claim.getStatus(), ClaimStatus.CLOSED);
        enforceChildrenTerminal(id);
        enforceRequiredPartReturnExists(claim);
        transition(claim, ClaimStatus.CLOSED, trimToNull(request.reason()));
        WarrantyClaim saved = claimRepository.save(claim);
        claimSnapshotPublisher.publish(id);
        return toDetail(saved);
    }

    @Override
    @NonNull
    @Transactional
    public ClaimResponse addNote(@NonNull UUID id, @NonNull ClaimNoteRequest request) {
        WarrantyClaim claim = load(id);
        noteRepository.save(
                ClaimNote.builder().claimId(claim.getId()).note(request.note()).build());
        return toDetail(claim);
    }

    // ------------------------------------------------------------------ internals

    @NonNull
    private WarrantyClaim load(@NonNull UUID id) {
        return claimRepository
                .findById(id)
                .orElseThrow(() ->
                        new WarrantyNotFoundException(NOT_FOUND_CODE, "Warranty claim '" + id + "' was not found"));
    }

    @NonNull
    private WarrantyClaim loadEditable(@NonNull UUID id) {
        WarrantyClaim claim = load(id);
        ClaimStateMachine.assertEditable(claim.getStatus());
        return claim;
    }

    /** Validates, applies, and audits a status change — every transition goes through here. */
    private void transition(@NonNull WarrantyClaim claim, @NonNull ClaimStatus to, @Nullable String reason) {
        ClaimStatus from = claim.getStatus();
        ClaimStateMachine.assertTransition(from, to);
        claim.setStatus(to);
        recordHistory(claim.getId(), from, to, reason);
    }

    private void recordHistory(
            @NonNull UUID claimId, @Nullable ClaimStatus from, @NonNull ClaimStatus to, @Nullable String reason) {
        statusHistoryRepository.save(ClaimStatusHistory.builder()
                .claimId(claimId)
                .fromStatus(from)
                .toStatus(to)
                .actor(currentActor())
                .reason(reason)
                .build());
    }

    @NonNull
    private ClaimResponse appeal(@NonNull WarrantyClaim claim, @Nullable String reason) {
        if (claim.getStatus() != ClaimStatus.DENIED) {
            throw ClaimStateMachine.illegalTransition(
                    claim.getStatus(), "appeal (only a DENIED claim can be appealed)");
        }
        if (reason == null) {
            throw new IllegalArgumentException("Appealing a denied claim requires a reason");
        }
        transition(claim, ClaimStatus.IN_REVIEW, "appeal: " + reason);
        // The claim is back under adjudication: clear the standing decision (the denial stays
        // fully audited in the status history and the appeal reason above).
        claim.setDecision(null);
        claim.setDecisionReason(null);
        claim.setDecidedBy(null);
        claim.setDecidedAt(null);
        claim.setOverrodeSuggestion(false);
        WarrantyClaim saved = claimRepository.save(claim);
        claimSnapshotPublisher.publish(claim.getId());
        return toDetail(saved);
    }

    /** Intake enforces at least one photo before submission when the winning policy demands it (PRD §3.2). */
    private void enforcePhotoEvidence(@NonNull WarrantyClaim claim) {
        if (claim.getPolicyId() == null) {
            return;
        }
        policyRepository.findById(claim.getPolicyId()).ifPresent(policy -> {
            boolean requiresPhotos = Boolean.TRUE.equals(policy.getRequiresPhotoEvidence());
            boolean hasPhotos = claim.getPhotoEvidenceUrls() != null
                    && !claim.getPhotoEvidenceUrls().isEmpty();
            if (requiresPhotos && !hasPhotos) {
                throw new IllegalArgumentException("Policy '" + policy.getName()
                        + "' requires photo evidence: attach at least one photo before submitting");
            }
        });
    }

    /** CLOSED requires every reimbursement and every part return terminal (PRD §5). */
    private void enforceChildrenTerminal(@NonNull UUID claimId) {
        long openReimbursements = reimbursementRepository.findByClaimId(claimId).stream()
                .filter(r -> !TERMINAL_REIMBURSEMENT.contains(r.getStatus()))
                .count();
        long openPartReturns = partReturnRepository.findByClaimId(claimId).stream()
                .filter(p -> !TERMINAL_PART_RETURN.contains(p.getStatus()))
                .count();
        if (openReimbursements > 0 || openPartReturns > 0) {
            throw new IllegalClaimStateException(
                    CLOSE_BLOCKED_CODE,
                    "Claim cannot be closed: " + openReimbursements + " vendor reimbursement(s) and " + openPartReturns
                            + " part return(s) are still open",
                    "Resolve every vendor reimbursement to a terminal status (CREDIT_RECEIVED, WRITTEN_OFF,"
                            + " NOT_APPLICABLE, DENIED) and every part return to a terminal status"
                            + " (RECEIVED_BY_VENDOR, SCRAPPED, CLOSED), then close the claim");
        }
    }

    /**
     * When the governing policy mandates returning the defective part (PRD §3.8
     * {@code requiresPartReturn}), an approved-and-settled claim cannot close until at least one
     * part return exists — {@link #enforceChildrenTerminal} then drives it to a terminal status.
     * DENIED→CLOSED claims skip this (nothing was covered, no RMA owed).
     */
    private void enforceRequiredPartReturnExists(@NonNull WarrantyClaim claim) {
        if (claim.getStatus() != ClaimStatus.SETTLED || claim.getPolicyId() == null) {
            return;
        }
        policyRepository.findById(claim.getPolicyId()).ifPresent(policy -> {
            if (Boolean.TRUE.equals(policy.getRequiresPartReturn())
                    && partReturnRepository.findByClaimId(claim.getId()).isEmpty()) {
                throw new IllegalClaimStateException(
                        CLOSE_BLOCKED_CODE,
                        "Claim cannot be closed: policy '" + policy.getName()
                                + "' requires the defective part to be returned and no part return exists",
                        "Open the RMA via POST /v1/warranty/claims/{id}/part-returns and drive it to a"
                                + " terminal status (RECEIVED_BY_VENDOR, SCRAPPED, CLOSED), then close the claim");
            }
        });
    }

    /**
     * Per-line adjudication (PRD §3.5, §6): on APPROVE/DENY, applies the request's explicit line
     * decisions and defaults the rest (APPROVE → APPROVED at the computed {@code amountRequested},
     * DENY → DENIED). An {@code amountApproved} differing from the computed amount requires an
     * {@code overrideReason}, audited as a claim note.
     *
     * @return whether any line's approved amount overrode the computed amount
     */
    private boolean applyLineDecisions(@NonNull WarrantyClaim claim, @NonNull ClaimDecisionRequest request) {
        List<ClaimDecisionRequest.LineDecision> decisions =
                request.lineDecisions() == null ? List.of() : request.lineDecisions();
        boolean adjudicating = request.decision() == ClaimDecisionRequest.Action.APPROVE
                || request.decision() == ClaimDecisionRequest.Action.DENY;
        if (!adjudicating) {
            if (!decisions.isEmpty()) {
                throw new IllegalArgumentException("lineDecisions are only accepted with APPROVE or DENY");
            }
            return false;
        }

        Map<UUID, ClaimDecisionRequest.LineDecision> byLineId = new LinkedHashMap<>();
        for (ClaimDecisionRequest.LineDecision decision : decisions) {
            if (byLineId.put(decision.lineId(), decision) != null) {
                throw new IllegalArgumentException(
                        "Duplicate line decision for claim line '" + decision.lineId() + "'");
            }
        }

        boolean approve = request.decision() == ClaimDecisionRequest.Action.APPROVE;
        LineDisposition defaultDisposition = approve ? LineDisposition.APPROVED : LineDisposition.DENIED;
        boolean anyOverride = false;
        for (WarrantyClaimLine line : claim.getLines()) {
            ClaimDecisionRequest.LineDecision decision = byLineId.remove(line.getId());
            if (decision == null) {
                line.setLineDisposition(defaultDisposition);
                line.setAmountApproved(approve ? line.getAmountRequested() : null);
                continue;
            }
            BigDecimal computed = line.getAmountRequested();
            BigDecimal amountApproved =
                    decision.amountApproved() != null ? decision.amountApproved() : (approve ? computed : null);
            boolean overridesComputed = decision.amountApproved() != null
                    && (computed == null || decision.amountApproved().compareTo(computed) != 0);
            String overrideReason = trimToNull(decision.overrideReason());
            if (overridesComputed && overrideReason == null) {
                throw new IllegalArgumentException("Claim line '" + line.getId() + "': amountApproved "
                        + decision.amountApproved() + " overrides the computed amountRequested " + computed
                        + "; an override reason is required");
            }
            line.setAmountApproved(amountApproved);
            line.setLineDisposition(
                    decision.lineDisposition() != null ? decision.lineDisposition() : defaultDisposition);
            if (overridesComputed) {
                anyOverride = true;
                noteRepository.save(ClaimNote.builder()
                        .claimId(claim.getId())
                        .note("line " + line.getId() + " amount override: " + overrideReason + " (computed " + computed
                                + ", approved " + decision.amountApproved() + ")")
                        .build());
            }
        }
        if (!byLineId.isEmpty()) {
            throw new WarrantyNotFoundException(
                    LINE_NOT_FOUND_CODE,
                    "Claim line(s) " + byLineId.keySet() + " were not found on claim '" + claim.getId() + "'");
        }
        return anyOverride;
    }

    private boolean contradictsSuggestion(@NonNull WarrantyClaim claim, ClaimDecisionRequest.@NonNull Action action) {
        EligibilityResult suggested = claim.getEligibilityResult();
        return (suggested == EligibilityResult.INELIGIBLE && action == ClaimDecisionRequest.Action.APPROVE)
                || (suggested == EligibilityResult.ELIGIBLE && action == ClaimDecisionRequest.Action.DENY);
    }

    @NonNull
    private static ClaimDecision toClaimDecision(ClaimDecisionRequest.@NonNull Action action) {
        return switch (action) {
            case APPROVE -> ClaimDecision.APPROVE;
            case DENY -> ClaimDecision.DENY;
            case REQUEST_INFO -> ClaimDecision.REQUEST_INFO;
            case APPEAL -> throw new IllegalArgumentException("APPEAL is not a persisted decision");
        };
    }

    @NonNull
    private static String currentActor() {
        return SecurityContextHelper.getCurrentUsernameOrDefault(DEFAULT_ACTOR);
    }

    @Nullable
    private static String trimToNull(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    @NonNull
    private static WarrantyClaimLine toLineEntity(@NonNull ClaimLineRequest request) {
        return WarrantyClaimLine.builder()
                .sourceType(request.sourceType())
                .sourceId(request.sourceId())
                .sourceLineId(request.sourceLineId())
                .productEntityId(request.productEntityId())
                .sku(request.sku())
                .description(request.description())
                .serialNumber(request.serialNumber())
                .dotNumber(request.dotNumber())
                .quantity(request.quantity() == null ? BigDecimal.ONE : request.quantity())
                .originalUnitPrice(request.originalUnitPrice())
                .originalTreadDepth(request.originalTreadDepth())
                .measuredTreadDepth(request.measuredTreadDepth())
                .build();
    }

    // ------------------------------------------------------------------ mapping

    @NonNull
    private ClaimResponse toDetail(@NonNull WarrantyClaim claim) {
        UUID claimId = claim.getId();
        return new ClaimResponse(
                claim.getId(),
                claim.getClaimCode(),
                claim.getClaimType(),
                claim.getStatus(),
                claim.getLocationId(),
                claim.getCustomerId(),
                claim.getVehicleId(),
                claim.getVin(),
                claim.getOdometerAtClaim(),
                claim.getOdometerUnit(),
                claim.getOriginWorkorderId(),
                claim.getOriginInvoiceId(),
                claim.getOriginSaleDate(),
                claim.getOriginUnverified(),
                claim.getProviderId(),
                claim.getPolicyId(),
                claim.getRegistrationId(),
                claim.getFailureDescription(),
                claim.getFailureDate(),
                claim.getPhotoEvidenceUrls(),
                claim.getEligibilityResult(),
                claim.getEligibilityReasons(),
                claim.getSuggestedOutcome(),
                claim.getDecision(),
                claim.getDecisionReason(),
                claim.getDecidedBy(),
                claim.getDecidedAt(),
                claim.getOverrodeSuggestion(),
                claim.getLines().stream().map(ClaimServiceImpl::toLineResponse).toList(),
                settlementRepository.findByClaimId(claimId).stream()
                        .map(s -> new ClaimResponse.SettlementView(
                                s.getId(),
                                s.getSettlementType(),
                                s.getStatus(),
                                s.getReplacementWorkorderId(),
                                s.getInvoiceId(),
                                s.getInvoiceAdjustmentId(),
                                s.getRefundRecordId(),
                                s.getCoveredAmount(),
                                s.getCustomerAmount(),
                                s.getCreatedAt()))
                        .toList(),
                reimbursementRepository.findByClaimId(claimId).stream()
                        .map(r -> new ClaimResponse.ReimbursementView(
                                r.getId(),
                                r.getProviderId(),
                                r.getStatus(),
                                r.getAmountRequested(),
                                r.getAmountApproved(),
                                r.getVendorClaimReference(),
                                r.getSubmittedAt(),
                                r.getSubmittedBy(),
                                r.getResolvedAt(),
                                r.getCreditReference(),
                                r.getCreditReceivedAt(),
                                r.getNotes()))
                        .toList(),
                partReturnRepository.findByClaimId(claimId).stream()
                        .map(p -> new ClaimResponse.PartReturnView(
                                p.getId(),
                                p.getClaimLineId(),
                                p.getRmaNumber(),
                                p.getDisposition(),
                                p.getStatus(),
                                p.getCarrier(),
                                p.getTrackingNumber(),
                                p.getShippedAt(),
                                p.getHoldLocationNote()))
                        .toList(),
                statusHistoryRepository.findByClaimIdOrderByCreatedAtAsc(claimId).stream()
                        .map(h -> new ClaimResponse.StatusHistoryView(
                                h.getId(),
                                h.getFromStatus(),
                                h.getToStatus(),
                                h.getActor(),
                                h.getReason(),
                                h.getCreatedAt()))
                        .toList(),
                noteRepository.findByClaimIdOrderByCreatedAtAsc(claimId).stream()
                        .map(n ->
                                new ClaimResponse.NoteView(n.getId(), n.getNote(), n.getCreatedBy(), n.getCreatedAt()))
                        .toList(),
                claim.getCreatedAt(),
                claim.getCreatedBy(),
                claim.getUpdatedAt(),
                claim.getVersion());
    }

    @NonNull
    private static ClaimLineResponse toLineResponse(@NonNull WarrantyClaimLine line) {
        return new ClaimLineResponse(
                line.getId(),
                line.getSourceType(),
                line.getSourceId(),
                line.getSourceLineId(),
                line.getProductEntityId(),
                line.getSku(),
                line.getDescription(),
                line.getSerialNumber(),
                line.getDotNumber(),
                line.getQuantity(),
                line.getOriginalUnitPrice(),
                line.getOriginalTreadDepth(),
                line.getMeasuredTreadDepth(),
                line.getProrationPct(),
                line.getProrationInputs(),
                line.getAmountRequested(),
                line.getAmountApproved(),
                line.getLineDisposition());
    }

    @NonNull
    private static ClaimSummaryResponse toSummary(@NonNull WarrantyClaim claim) {
        return new ClaimSummaryResponse(
                claim.getId(),
                claim.getClaimCode(),
                claim.getClaimType(),
                claim.getStatus(),
                claim.getCustomerId(),
                claim.getVehicleId(),
                claim.getVin(),
                claim.getLocationId(),
                claim.getFailureDate(),
                claim.getEligibilityResult(),
                claim.getCreatedAt(),
                claim.getUpdatedAt());
    }
}
